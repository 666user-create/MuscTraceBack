package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlatformVersion;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.infrastructure.cache.SearchCache;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * VersionDiscoveryService 测试:来源平台零请求入库、其他平台 Top3、
 * 幂等去重、热度百分位。
 */
class VersionDiscoveryServiceTests {

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
        @Override public String playUrl(String s) { return "https://x/" + s; }
    }

    private VersionDiscoveryService service(PlatformVersionRepository repo,
                                            IdentityResolutionService identity,
                                            List<PlatformAdapter> adapters) {
        return new VersionDiscoveryService(repo, identity, new SearchCache(60000), adapters);
    }

    private IdentityResolutionService mockIdentity(PlatformAdapter adapter, List<TrackCandidate> candidates) {
        IdentityResolutionService identity = mock(IdentityResolutionService.class);
        when(identity.searchCached(eq(adapter), any(), any())).thenReturn(candidates);
        return identity;
    }

    @Test
    void discoverOtherPlatforms_skipsSourcePlatform() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var source = new FakeAdapter("netease", List.of());
        var qq = new FakeAdapter("qq", List.of(
                new TrackCandidate("Q1", "晴天", "周杰伦", "叶惠美", 269, 95L, "")));
        var kugou = new FakeAdapter("kugou", List.of(
                new TrackCandidate("K1", "晴天", "周杰伦", "叶惠美", 269, 80L, "")));

        IdentityResolutionService identity = mock(IdentityResolutionService.class);
        when(identity.searchCached(eq(qq), any(), any())).thenReturn(qq.candidates);
        when(identity.searchCached(eq(kugou), any(), any())).thenReturn(kugou.candidates);
        when(identity.searchCached(eq(source), any(), any())).thenThrow(new AssertionError("不应搜索来源平台"));

        var service = service(repo, identity, List.of(source, qq, kugou));
        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();

        List<PlatformVersion> top = service.discoverOtherPlatforms(track, "晴天", "周杰伦", "netease");

        assertThat(top).hasSize(2);
        assertThat(top).extracting(PlatformVersion::getPlatform)
                .containsExactlyInAnyOrder("qq", "kugou");
        assertThat(track.getPlatformVersions()).hasSize(2);
        verify(repo, times(1)).saveAll(any());
    }

    @Test
    void discoverOtherPlatforms_takesTop3PerPlatform() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var qq = new FakeAdapter("qq", List.of(
                new TrackCandidate("1", "晴天", "周杰伦", "叶惠美", 269, 10L, ""),
                new TrackCandidate("2", "晴天", "周杰伦", "叶惠美", 269, 90L, ""),
                new TrackCandidate("3", "晴天", "周杰伦", "叶惠美", 269, 60L, ""),
                new TrackCandidate("4", "晴天", "周杰伦", "叶惠美", 269, 99L, ""),
                new TrackCandidate("5", "晴天", "某翻唱者", "cover", 200, 100L, "")));
        var service = service(repo, mockIdentity(qq, qq.candidates), List.of(qq));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        List<PlatformVersion> top = service.discoverOtherPlatforms(track, "晴天", "周杰伦", "netease");

        assertThat(top).hasSize(3); // 每平台最多 3 个
        // 置信度优先:原版(高置信)在前,翻唱(低置信)即使热度最高也不进
        assertThat(top).allMatch(v -> v.getArtist().equals("周杰伦"));
        assertThat(top.get(0).getPopularityRaw()).isEqualTo(99L); // 热度降序:99 > 90 > 60
        assertThat(top.get(1).getPopularityRaw()).isEqualTo(90L);
        assertThat(top.get(2).getPopularityRaw()).isEqualTo(60L);
    }

    @Test
    void addSourceVersion_zeroRequest_addsOriginal() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByCanonicalTrackId(1L)).thenReturn(List.of());

        var source = new FakeAdapter("netease", List.of());
        var service = service(repo, mock(IdentityResolutionService.class), List.of(source));

        CanonicalTrack track = CanonicalTrack.builder().id(1L)
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();

        service.addSourceVersion(track, "netease", "1001", "晴天", "周杰伦", "叶惠美");

        assertThat(track.getPlatformVersions()).hasSize(1);
        PlatformVersion v = track.getPlatformVersions().get(0);
        assertThat(v.getPlatform()).isEqualTo("netease");
        assertThat(v.getPlatformTrackId()).isEqualTo("1001");
        assertThat(v.getVersionType()).isEqualTo(VersionType.ORIGINAL);
        assertThat(v.getScore()).isEqualTo(org.example.musictraceback.domain.identity.VersionScorer.HIGH_THRESHOLD);
        verify(repo, times(1)).save(any());
    }

    @Test
    void addSourceVersion_idempotent_skipsExisting() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        PlatformVersion existing = PlatformVersion.builder()
                .platform("netease").platformTrackId("1001")
                .title("晴天").artist("周杰伦")
                .versionType(VersionType.ORIGINAL).score(95).build();
        when(repo.findByCanonicalTrackId(1L)).thenReturn(List.of(existing));

        var service = service(repo, mock(IdentityResolutionService.class), List.of(new FakeAdapter("netease", List.of())));
        CanonicalTrack track = CanonicalTrack.builder().id(1L)
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();

        service.addSourceVersion(track, "netease", "1001", "晴天", "周杰伦", "叶惠美");

        assertThat(track.getPlatformVersions()).isEmpty(); // 已存在,不重复添加
        verify(repo, never()).save(any());
    }

    @Test
    void discoverOtherPlatforms_computesPercentile() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var qq = new FakeAdapter("qq", List.of(
                new TrackCandidate("1", "晴天", "周杰伦", "叶惠美", 269, 50L, ""),
                new TrackCandidate("2", "晴天", "周杰伦", "叶惠美", 269, 100L, ""),
                new TrackCandidate("3", "晴天", "周杰伦", "叶惠美", 269, 0L, "")));
        var service = service(repo, mockIdentity(qq, qq.candidates), List.of(qq));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        List<PlatformVersion> top = service.discoverOtherPlatforms(track, "晴天", "周杰伦", "netease");

        assertThat(top).hasSize(3);
        PlatformVersion hottest = top.stream()
                .filter(v -> v.getPopularityRaw() != null && v.getPopularityRaw() == 100L)
                .findFirst().orElseThrow();
        assertThat(hottest.getPopularityPercentile()).isEqualTo(100.0);
    }

    @Test
    void discoverOtherPlatforms_noCandidates_returnsEmpty() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        var qq = new FakeAdapter("qq", List.of());
        var service = service(repo, mockIdentity(qq, List.of()), List.of(qq));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("不存在").normalizedArtist("不存在").build();
        List<PlatformVersion> top = service.discoverOtherPlatforms(track, "不存在", "不存在", "netease");

        assertThat(top).isEmpty();
        verify(repo, never()).saveAll(any());
    }
}
