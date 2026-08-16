package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlatformVersion;
import org.example.musictraceback.domain.identity.VersionScorer;
import org.example.musictraceback.infrastructure.cache.SearchCache;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 版本发现与 Top 3 聚合(设计文档 FR-3 / FR-4)。
 *
 * 风控优化设计(关键):
 * 1. 来源平台版本零请求:歌单接口已返回该平台的条目数据,直接入库;
 * 2. 仅对其他平台发起搜索,每平台各取 Top3;
 * 3. 搜索经 SearchCache 缓存复用。
 */
@Service
public class VersionDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(VersionDiscoveryService.class);
    private static final int TOP_N_PER_PLATFORM = 3;

    private final PlatformVersionRepository platformVersionRepository;
    private final IdentityResolutionService identityResolutionService;
    private final SearchCache searchCache;
    private final List<PlatformAdapter> adapters;

    public VersionDiscoveryService(PlatformVersionRepository platformVersionRepository,
                                   IdentityResolutionService identityResolutionService,
                                   SearchCache searchCache,
                                   List<PlatformAdapter> adapters) {
        this.platformVersionRepository = platformVersionRepository;
        this.identityResolutionService = identityResolutionService;
        this.searchCache = searchCache;
        this.adapters = adapters;
    }

    /**
     * 来源平台版本零请求入库:用歌单条目的原始数据建版本。
     * 幂等:已存在相同 (platform, platformTrackId) 则跳过。
     */
    public void addSourceVersion(CanonicalTrack track, String sourcePlatformId,
                                 String platformTrackId, String title, String artist, String album) {
        if (track.getId() == null || platformTrackId == null || platformTrackId.isBlank()) {
            return;
        }
        boolean exists = platformVersionRepository.findByCanonicalTrackId(track.getId()).stream()
                .anyMatch(v -> v.getPlatform().equals(sourcePlatformId)
                        && v.getPlatformTrackId().equals(platformTrackId));
        if (exists) {
            return;
        }
        // 全局去重:该曲目已被其他身份收录则跳过(歌单条目 ID 可能与搜索候选重复)
        if (platformVersionRepository.existsByPlatformAndPlatformTrackId(sourcePlatformId, platformTrackId)) {
            return;
        }
        PlatformAdapter sourceAdapter = adapters.stream()
                .filter(a -> a.platformId().equals(sourcePlatformId))
                .findFirst().orElse(null);

        PlatformVersion v = PlatformVersion.builder()
                .platform(sourcePlatformId)
                .platformTrackId(platformTrackId)
                .title(title)
                .artist(artist)
                .album(album)
                .versionType(org.example.musictraceback.domain.enums.VersionType.ORIGINAL)
                .playUrl(sourceAdapter != null ? sourceAdapter.playUrl(platformTrackId) : "")
                .score(VersionScorer.HIGH_THRESHOLD)
                .confidence(org.example.musictraceback.domain.enums.Confidence.HIGH)
                .build();
        track.addVersion(v);
        platformVersionRepository.save(v);
    }

    /**
     * 对其他平台(排除来源平台)搜索并保存各平台 Top3。
     * 平台搜索失败/风控由调用方(InjectionProcessor)处理,这里尽力而为:
     * 单个平台异常不阻断其他平台。
     *
     * @return 本次新保存的版本列表
     */
    public List<PlatformVersion> discoverOtherPlatforms(CanonicalTrack track, String sourceTitle,
                                                        String sourceArtist, String sourcePlatformId) {
        Set<String> existingKeys = track.getId() == null
                ? Set.of()
                : platformVersionRepository.findByCanonicalTrackId(track.getId()).stream()
                        .map(v -> v.getPlatform() + "::" + v.getPlatformTrackId())
                        .collect(Collectors.toSet());

        List<PlatformVersion> allNew = new ArrayList<>();

        for (PlatformAdapter adapter : adapters) {
            if (adapter.platformId().equals(sourcePlatformId)) {
                continue; // 来源平台已零请求入库
            }
            List<TrackCandidate> candidates;
            try {
                candidates = searchCached(adapter, sourceTitle, sourceArtist);
            } catch (org.example.musictraceback.infrastructure.http.HttpFetcher.RateLimitedException e) {
                // 风控/冷却:向上抛,由 InjectionProcessor 感知并暂停重试
                throw e;
            } catch (Exception e) {
                log.warn("平台 {} 搜索失败: {}", adapter.platformId(), e.getMessage());
                continue;
            }

            // 本平台候选:打分 → 排除已存在(当前身份 + 全局)→ 排序取 Top3
            List<PlatformVersion> platformTop = candidates.stream()
                    .filter(c -> !existingKeys.contains(adapter.platformId() + "::" + c.platformTrackId()))
                    // 全局去重:该平台曲目已被其他身份收录则跳过(搜索结果常含同名重复曲目)
                    .filter(c -> !platformVersionRepository
                            .existsByPlatformAndPlatformTrackId(adapter.platformId(), c.platformTrackId()))
                    .map(c -> {
                        VersionScorer.ScoreResult r = VersionScorer.score(
                                c.title(), c.artist(), c.album(), c.durationSec(), c.popularity(),
                                sourceTitle, sourceArtist, null);
                        return PlatformVersion.builder()
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
                    })
                    .sorted(Comparator.comparing(PlatformVersion::getConfidenceRank).reversed()
                            .thenComparing(PlatformVersion::getPopularityRaw,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(TOP_N_PER_PLATFORM)
                    .toList();

            if (platformTop.isEmpty()) {
                continue;
            }
            // 本平台内热度百分位
            computePercentile(platformTop);
            for (PlatformVersion v : platformTop) {
                track.addVersion(v);
                allNew.add(v);
            }
        }

        if (allNew.isEmpty()) {
            return List.of();
        }
        return platformVersionRepository.saveAll(allNew);
    }

    /** 带缓存的平台搜索 */
    private List<TrackCandidate> searchCached(PlatformAdapter adapter, String title, String artist) {
        String keyword = (title + " " + artist).trim();
        List<TrackCandidate> cached = searchCache.get(adapter.platformId(), keyword);
        if (cached != null) {
            return cached;
        }
        List<TrackCandidate> result = adapter.search(title, artist);
        searchCache.put(adapter.platformId(), keyword, result);
        return result;
    }

    /** 平台内热度百分位(0-100,最高为 100) */
    private void computePercentile(List<PlatformVersion> versions) {
        int n = versions.size();
        List<PlatformVersion> sorted = versions.stream()
                .sorted(Comparator.comparing(PlatformVersion::getPopularityRaw,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (int i = 0; i < n; i++) {
            double percentile = n == 1 ? 100.0 : (double) i / (n - 1) * 100.0;
            sorted.get(i).setPopularityPercentile(Math.round(percentile * 10.0) / 10.0);
        }
    }
}
