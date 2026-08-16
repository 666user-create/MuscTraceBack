package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.infrastructure.repository.CanonicalTrackRepository;
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
 * PlaylistInjectionService 编排测试:
 * 真实 Repository(内存 H2)+ mock 适配器,服务手动组装(不依赖 Spring 容器装配)。
 */
@DataJpaTest
class PlaylistInjectionServiceTests {

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private CanonicalTrackRepository canonicalTrackRepository;

    @Autowired
    private PlatformVersionRepository platformVersionRepository;

    private PlatformAdapter adapter;
    private PlaylistInjectionService injectionService;

    @BeforeEach
    void setUp() {
        adapter = mock(PlatformAdapter.class);
        when(adapter.platformId()).thenReturn("netease");
        IdentityResolutionService identity =
                new IdentityResolutionService(canonicalTrackRepository, List.of(adapter));
        VersionDiscoveryService discovery =
                new VersionDiscoveryService(platformVersionRepository, List.of(adapter));
        injectionService = new PlaylistInjectionService(
                snapshotRepository, identity, discovery, List.of(adapter));
    }

    @Test
    void inject_noSearchableCandidates_completesWithLowConfidence() {
        when(adapter.parsePlaylist(any())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美"),
                new PlaylistEntryRaw("1002", "七里香", "周杰伦", "七里香")));
        when(adapter.search(any(), any())).thenReturn(List.of());

        Snapshot snapshot = injectionService.inject("https://music.163.com/#/playlist?id=12345");

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETED");
        assertThat(snapshot.getPlatform()).isEqualTo("netease");
        assertThat(snapshot.getPlaylistEntries()).hasSize(2);

        Snapshot reloaded = snapshotRepository.findById(snapshot.getId()).orElseThrow();
        PlaylistEntry first = reloaded.getPlaylistEntries().stream()
                .filter(e -> e.getPlatformTrackId().equals("1001"))
                .findFirst().orElseThrow();
        assertThat(first.getRawTitle()).isEqualTo("晴天");
        assertThat(first.getConfidence()).isEqualTo(Confidence.LOW);
        assertThat(first.getCanonicalTrack()).isNull();
    }

    @Test
    void inject_unsupportedPlatform_throws() {
        assertThatThrownBy(() -> injectionService.inject("https://example.com/playlist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别歌单平台");
    }

    @Test
    void inject_parseFailure_marksFailed() {
        when(adapter.parsePlaylist(any())).thenThrow(new RuntimeException("接口超时"));

        Snapshot snapshot = injectionService.inject("https://music.163.com/#/playlist?id=1");
        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void inject_searchableAdapter_buildsIdentityAndVersions() {
        when(adapter.parsePlaylist(any())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));
        when(adapter.search("晴天", "周杰伦")).thenReturn(List.of(
                new TrackCandidate("2001", "晴天", "周杰伦", "叶惠美", 269, 100L, "https://x/2001")));
        when(adapter.playUrl(any())).thenReturn("https://x/2001");

        Snapshot snapshot = injectionService.inject("https://music.163.com/#/playlist?id=9");

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETED");
        PlaylistEntry entry = snapshot.getPlaylistEntries().get(0);
        assertThat(entry.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(entry.getCanonicalTrack()).isNotNull();
        assertThat(entry.getCanonicalTrack().getNormalizedTitle()).isEqualTo("晴天");

        assertThat(canonicalTrackRepository.count()).isEqualTo(1);
        assertThat(platformVersionRepository.count()).isGreaterThanOrEqualTo(1);
        assertThat(platformVersionRepository.findByCanonicalTrackId(
                entry.getCanonicalTrack().getId())).isNotEmpty();
    }
}
