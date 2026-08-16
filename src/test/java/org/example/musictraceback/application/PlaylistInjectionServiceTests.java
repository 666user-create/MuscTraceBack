package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.EntryStatus;
import org.example.musictraceback.infrastructure.cache.SearchCache;
import org.example.musictraceback.infrastructure.repository.CanonicalTrackRepository;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlaylistInjectionService 编排测试(异步架构):
 * 真实 Repository(内存 H2)+ 真实处理链路 + mock 适配器。
 * 测试环境无 @EnableAsync 代理,processSnapshot 同步执行,便于断言结果。
 */
@DataJpaTest
class PlaylistInjectionServiceTests {

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private PlaylistEntryRepository playlistEntryRepository;

    @Autowired
    private CanonicalTrackRepository canonicalTrackRepository;

    @Autowired
    private PlatformVersionRepository platformVersionRepository;

    private PlatformAdapter adapter;
    private PlaylistInjectionService injectionService;
    private InjectionProcessor processor;

    @BeforeEach
    void setUp() {
        adapter = mock(PlatformAdapter.class);
        when(adapter.platformId()).thenReturn("netease");
        when(adapter.playUrl(any())).thenReturn("https://x/");
        IdentityResolutionService identity =
                new IdentityResolutionService(canonicalTrackRepository, new SearchCache(60000), List.of(adapter));
        VersionDiscoveryService discovery =
                new VersionDiscoveryService(platformVersionRepository, identity, new SearchCache(60000), List.of(adapter));
        EntryProcessingService entryProcessing =
                new EntryProcessingService(playlistEntryRepository, identity, discovery);
        processor = new InjectionProcessor(snapshotRepository, playlistEntryRepository, entryProcessing);
        injectionService = new PlaylistInjectionService(
                snapshotRepository, playlistEntryRepository, processor, List.of(adapter));
    }

    @Test
    void inject_buildsSnapshot_andProcessesEntries() {
        when(adapter.parsePlaylist(any())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美"),
                new PlaylistEntryRaw("1002", "七里香", "周杰伦", "七里香")));

        Snapshot snapshot = injectionService.inject("https://music.163.com/#/playlist?id=12345");
        // 测试环境:手动触发后台处理(生产由 @Async 自动执行)
        processor.processSnapshot(snapshot.getId());

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETED");
        assertThat(snapshot.getPlatform()).isEqualTo("netease");

        // 异步处理已同步完成:全部条目 processed
        assertThat(playlistEntryRepository.countBySnapshotIdAndProcessedTrue(snapshot.getId())).isEqualTo(2);
        // 本地建档:身份 HIGH(零请求),状态 NORMAL
        PlaylistEntry first = playlistEntryRepository.findBySnapshotId(snapshot.getId()).stream()
                .filter(e -> e.getPlatformTrackId().equals("1001"))
                .findFirst().orElseThrow();
        assertThat(first.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(first.getCanonicalTrack()).isNotNull();
        assertThat(first.getEntryStatus()).isEqualTo(EntryStatus.NORMAL);
        assertThat(first.getCanonicalTrack().getNormalizedTitle()).isEqualTo("晴天");
        // 来源平台版本零请求入库
        assertThat(platformVersionRepository.findByCanonicalTrackId(
                first.getCanonicalTrack().getId())).isNotEmpty();
    }

    @Test
    void inject_unsupportedPlatform_throws() {
        assertThatThrownBy(() -> injectionService.inject("https://example.com/playlist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别歌单平台");
    }

    @Test
    void inject_parseFailure_throws() {
        when(adapter.parsePlaylist(any())).thenThrow(new RuntimeException("接口超时"));

        assertThatThrownBy(() -> injectionService.inject("https://music.163.com/#/playlist?id=1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("歌单解析失败");
    }

    @Test
    void inject_emptyPlaylist_throws() {
        when(adapter.parsePlaylist(any())).thenReturn(List.of());
        assertThatThrownBy(() -> injectionService.inject("https://music.163.com/#/playlist?id=1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("歌单为空");
    }

    @Test
    void resume_restartsProcessing() {
        when(adapter.parsePlaylist(any())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));

        Snapshot snapshot = injectionService.inject("https://music.163.com/#/playlist?id=9");
        processor.processSnapshot(snapshot.getId());
        assertThat(snapshot.getStatus()).isEqualTo("COMPLETED");

        // COMPLETED 且无未处理 → resume 幂等不重复
        injectionService.resume(snapshot.getId());
        assertThat(snapshotRepository.findById(snapshot.getId()).orElseThrow().getStatus())
                .isEqualTo("COMPLETED");
        assertThat(playlistEntryRepository.findBySnapshotIdAndProcessedFalse(snapshot.getId())).isEmpty();
    }
}
