package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlatformVersion;
import org.example.musictraceback.domain.identity.VersionScorer;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版本发现与 Top 3 聚合(设计文档 FR-3 / FR-4)。
 *
 * 流程:真实身份 → 遍历已注册平台搜索候选 → 打分判类型 →
 *      按热度归一化排序 → 取 Top 3 落库。
 */
@Service
public class VersionDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(VersionDiscoveryService.class);
    private static final int TOP_N = 3;

    private final PlatformVersionRepository platformVersionRepository;
    private final IdentityResolutionService identityResolutionService;
    private final List<PlatformAdapter> adapters;

    public VersionDiscoveryService(PlatformVersionRepository platformVersionRepository,
                                   IdentityResolutionService identityResolutionService,
                                   List<PlatformAdapter> adapters) {
        this.platformVersionRepository = platformVersionRepository;
        this.identityResolutionService = identityResolutionService;
        this.adapters = adapters;
    }

    /**
     * 为真实身份发现并保存 Top 3 版本。
     * 幂等:已存在相同 (platform, platformTrackId) 的版本会被跳过,
     * 重复注入同一歌单不会产生唯一约束冲突。
     *
     * @param track       真实身份
     * @param sourceTitle 原始歌名(用于打分比较,保留用户歌单的原始写法)
     * @param sourceArtist 原始歌手
     */
    public List<PlatformVersion> discover(CanonicalTrack track, String sourceTitle, String sourceArtist) {
        // 已存在的版本(用于幂等去重)
        java.util.Set<String> existingKeys = track.getId() == null
                ? java.util.Set.of()
                : platformVersionRepository.findByCanonicalTrackId(track.getId()).stream()
                        .map(v -> v.getPlatform() + "::" + v.getPlatformTrackId())
                        .collect(java.util.stream.Collectors.toSet());

        List<PlatformVersion> all = new ArrayList<>();

        for (PlatformAdapter adapter : adapters) {
            // 复用身份解析的搜索缓存(同一关键词不重复请求平台)
            List<TrackCandidate> candidates = identityResolutionService.searchCached(adapter, sourceTitle, sourceArtist);
            for (TrackCandidate c : candidates) {
                String key = adapter.platformId() + "::" + c.platformTrackId();
                if (existingKeys.contains(key)) {
                    continue; // 幂等:跳过已存在的版本
                }
                VersionScorer.ScoreResult r = VersionScorer.score(
                        c.title(), c.artist(), c.album(), c.durationSec(), c.popularity(),
                        sourceTitle, sourceArtist, null);

                PlatformVersion v = PlatformVersion.builder()
                        .platform(adapter.platformId())
                        .platformTrackId(c.platformTrackId())
                        .title(c.title())
                        .artist(c.artist())
                        .album(c.album())
                        .durationSec(c.durationSec())
                        .versionType(r.versionType())
                        .popularityRaw(c.popularity())
                        .playUrl(c.playUrl())
                        .score(r.score())
                        .confidence(r.confidence())
                        .build();
                all.add(v);
            }
        }

        // 计算平台内热度百分位:同一平台内按热度排序
        computePercentile(all);

        // 聚合排序:先置信度高(原版优先),再热度(均降序)
        List<PlatformVersion> ranked = all.stream()
                .sorted(Comparator.comparing(PlatformVersion::getConfidenceRank).reversed()
                        .thenComparing(PlatformVersion::getPopularityRaw,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(TOP_N)
                .toList();

        if (ranked.isEmpty()) {
            log.info("身份 {} - {} 无候选版本", track.getNormalizedTitle(), track.getNormalizedArtist());
            return List.of();
        }

        // 关联身份并落库
        for (PlatformVersion v : ranked) {
            track.addVersion(v);
        }
        List<PlatformVersion> saved = platformVersionRepository.saveAll(ranked);
        log.info("身份 {} - {} 发现 {} 个候选,保存 Top{}",
                track.getNormalizedTitle(), track.getNormalizedArtist(), all.size(), TOP_N);
        return saved;
    }

    /** 同一平台内热度百分位(0-100,最高为 100) */
    private void computePercentile(List<PlatformVersion> all) {
        for (String platform : all.stream().map(PlatformVersion::getPlatform).distinct().toList()) {
            List<PlatformVersion> same = all.stream()
                    .filter(v -> v.getPlatform().equals(platform))
                    .sorted(Comparator.comparing(PlatformVersion::getPopularityRaw,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            int n = same.size();
            for (int i = 0; i < n; i++) {
                double percentile = n == 1 ? 100.0 : (double) i / (n - 1) * 100.0;
                same.get(i).setPopularityPercentile(Math.round(percentile * 10.0) / 10.0);
            }
        }
    }
}
