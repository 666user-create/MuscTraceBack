package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlatformVersion;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * VersionDiscoveryService 测试:候选聚合、Top 3 排序、百分位计算。
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

    @Test
    void discover_ranksTop3_byConfidenceThenPopularity() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // 5 个候选:原版(高置信、热度中)、翻唱(低置信、热度高)、Live(中置信)
        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("1", "晴天", "周杰伦", "叶惠美", 269, 60L, ""),
                new TrackCandidate("2", "晴天", "周杰伦", "叶惠美", 269, 95L, ""),
                new TrackCandidate("3", "晴天", "某翻唱者", "cover", 200, 99L, ""),
                new TrackCandidate("4", "晴天 (Live)", "周杰伦", "现场专辑", 300, 80L, ""),
                new TrackCandidate("5", "晴天", "周杰伦", "叶惠美", 269, 50L, "")));
        var service = new VersionDiscoveryService(repo, List.of(adapter));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();

        List<PlatformVersion> top = service.discover(track, "晴天", "周杰伦");

        assertThat(top).hasSize(3);
        // 置信度优先:原版(高置信)在前,翻唱(低置信)即使热度最高也不进 Top 3
        assertThat(top).allMatch(v -> v.getVersionType() == VersionType.ORIGINAL);
        assertThat(top).allMatch(v -> v.getArtist().equals("周杰伦"));
        // Top 3 按热度降序:95 > 60 > 50
        assertThat(top.get(0).getPopularityRaw()).isEqualTo(95L);
        assertThat(top.get(1).getPopularityRaw()).isEqualTo(60L);
        assertThat(top.get(2).getPopularityRaw()).isEqualTo(50L);

        // 版本已关联到身份
        assertThat(track.getPlatformVersions()).hasSize(3);
        verify(repo, times(1)).saveAll(any());
    }

    @Test
    void discover_computesPercentileWithinPlatform() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("1", "晴天", "周杰伦", "叶惠美", 269, 50L, ""),
                new TrackCandidate("2", "晴天", "周杰伦", "叶惠美", 269, 100L, ""),
                new TrackCandidate("3", "晴天", "周杰伦", "叶惠美", 269, 0L, "")));
        var service = new VersionDiscoveryService(repo, List.of(adapter));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        List<PlatformVersion> top = service.discover(track, "晴天", "周杰伦");

        // 只有 3 个候选,全部进 Top 3;热度最高者百分位应为 100
        assertThat(top).hasSize(3);
        PlatformVersion hottest = top.stream()
                .filter(v -> v.getPopularityRaw() != null && v.getPopularityRaw() == 100L)
                .findFirst().orElseThrow();
        assertThat(hottest.getPopularityPercentile()).isEqualTo(100.0);
    }

    @Test
    void discover_noCandidates_returnsEmpty() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        var service = new VersionDiscoveryService(repo, List.of(new FakeAdapter("netease", List.of())));

        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("不存在").normalizedArtist("不存在").build();
        List<PlatformVersion> top = service.discover(track, "不存在", "不存在");

        assertThat(top).isEmpty();
        verify(repo, never()).saveAll(any());
    }

    @Test
    void discover_skipsExistingVersions_idempotent() {
        PlatformVersionRepository repo = mock(PlatformVersionRepository.class);
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // 已有版本:netease::1 已存在(模拟首次注入保存的)
        PlatformVersion existing = PlatformVersion.builder()
                .platform("netease").platformTrackId("1")
                .title("晴天").artist("周杰伦")
                .versionType(VersionType.ORIGINAL).score(95).build();
        when(repo.findByCanonicalTrackId(1L)).thenReturn(List.of(existing));

        CanonicalTrack track = CanonicalTrack.builder().id(1L)
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();

        var adapter = new FakeAdapter("netease", List.of(
                new TrackCandidate("1", "晴天", "周杰伦", "叶惠美", 269, 95L, ""), // 已存在,应跳过
                new TrackCandidate("2", "晴天", "周杰伦", "叶惠美", 269, 90L, ""))); // 新,应保留
        var service = new VersionDiscoveryService(repo, List.of(adapter));

        List<PlatformVersion> top = service.discover(track, "晴天", "周杰伦");

        // 只保存了新版本 2
        assertThat(top).hasSize(1);
        assertThat(top.get(0).getPlatformTrackId()).isEqualTo("2");
    }
}
