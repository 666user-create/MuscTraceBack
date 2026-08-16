package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.infrastructure.cache.SearchCache;
import org.example.musictraceback.infrastructure.repository.CanonicalTrackRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IdentityResolutionService 测试(Mockito + 真实 SearchCache,无网络)。
 * 覆盖:命中缓存 / 高置信解析 / 低置信不建身份 / 空结果 / 无适配器 / 按来源平台。
 */
class IdentityResolutionServiceTests {

    /** 假适配器:固定返回候选 */
    static class FakeAdapter implements PlatformAdapter {
        final String id;
        final List<TrackCandidate> candidates;

        FakeAdapter(String id, List<TrackCandidate> candidates) {
            this.id = id;
            this.candidates = candidates;
        }

        @Override public String platformId() { return id; }
        @Override public List<PlaylistEntryRaw> parsePlaylist(String s) { return List.of(); }
        @Override public List<TrackCandidate> search(String title, String artist) { return candidates; }
        @Override public TrackCandidate detail(String s) { return null; }
        @Override public String playUrl(String s) { return ""; }
    }

    private IdentityResolutionService service(CanonicalTrackRepository repo, List<PlatformAdapter> adapters) {
        return new IdentityResolutionService(repo, new SearchCache(60000), adapters);
    }

    @Test
    void resolve_highConfidence_createsTrack() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        when(repo.findByNormalizedTitleAndNormalizedArtist(any(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any(CanonicalTrack.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("2001", "晴天", "周杰伦", "叶惠美", 269, 100L, "")));
        var service = service(repo, List.of(adapter));

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isNotNull();
        assertThat(result.track().getNormalizedTitle()).isEqualTo("晴天");
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        verify(repo, times(1)).save(any(CanonicalTrack.class));
    }

    @Test
    void resolve_hitsCache_reusesExisting() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        CanonicalTrack existing = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        when(repo.findByNormalizedTitleAndNormalizedArtist("晴天", "周杰伦"))
                .thenReturn(Optional.of(existing));

        // 适配器即使返回空,命中身份缓存也不该触发搜索
        var service = service(repo, List.of(new FakeAdapter("netease", List.of())));

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isSameAs(existing);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        verify(repo, never()).save(any());
    }

    @Test
    void resolve_lowConfidence_doesNotCreateTrack() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        when(repo.findByNormalizedTitleAndNormalizedArtist(any(), any()))
                .thenReturn(Optional.empty());

        // 完全无关的候选 → 低置信
        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("9999", "完全不同的歌", "完全无关的人", null, null, null, "")));
        var service = service(repo, List.of(adapter));

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isNull();
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        verify(repo, never()).save(any());
    }

    @Test
    void resolve_noCandidates_returnsLow() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        when(repo.findByNormalizedTitleAndNormalizedArtist(any(), any()))
                .thenReturn(Optional.empty());
        var service = service(repo, List.of(new FakeAdapter("netease", List.of())));

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isNull();
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        verify(repo, never()).save(any());
    }

    @Test
    void resolve_noAdapter_returnsLow() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        var service = service(repo, List.of());

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isNull();
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void resolve_mixedCandidate_picksBestByScore() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        when(repo.findByNormalizedTitleAndNormalizedArtist(any(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any(CanonicalTrack.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("1001", "晴天", "某翻唱者", "cover", 200, 5L, ""),
                new TrackCandidate("1002", "晴天", "周杰伦", "叶惠美", 269, 99L, "")));
        var service = service(repo, List.of(adapter));

        var result = service.resolve("晴天", "周杰伦", "netease");

        assertThat(result.track()).isNotNull();
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.judgedVersionType()).isEqualTo(VersionType.ORIGINAL);
    }

    @Test
    void resolve_usesSourcePlatformAdapter() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        when(repo.findByNormalizedTitleAndNormalizedArtist(any(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any(CanonicalTrack.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // QQ 适配器有候选,网易云为空 → 来源平台=qq 时应解析成功
        var qqAdapter = new FakeAdapter("qq", List.of(
                new TrackCandidate("Q1", "晴天", "周杰伦", "叶惠美", 269, 0L, "")));
        var neteaseAdapter = new FakeAdapter("netease", List.of());
        var service = service(repo, List.of(neteaseAdapter, qqAdapter));

        var result = service.resolve("晴天", "周杰伦", "qq");

        assertThat(result.track()).isNotNull();
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void searchCached_returnsCachedResultWithoutSecondCall() {
        CanonicalTrackRepository repo = mock(CanonicalTrackRepository.class);
        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("2001", "晴天", "周杰伦", "叶惠美", 269, 100L, "")));
        var service = service(repo, List.of(adapter));

        var first = service.searchCached(adapter, "晴天", "周杰伦");
        var second = service.searchCached(adapter, "晴天", "周杰伦");

        assertThat(first).hasSize(1);
        assertThat(second).isSameAs(first); // 第二次命中缓存(同一 List 实例)
    }
}
