package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.domain.identity.TextNormalizer;
import org.example.musictraceback.domain.identity.VersionScorer;
import org.example.musictraceback.infrastructure.repository.CanonicalTrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 身份解析服务(设计文档 FR-2 / 9 节第 1-3 层)。
 *
 * 流程:歌单条目 → 规范化 → 平台搜索 → 打分 → 生成/复用真实身份。
 * 输出:CanonicalTrack + 置信度。置信度分级由打分结果决定,
 * 中/低置信条目由前端引导用户确认(第 4 层兜底,见 UserConfirmationService)。
 */
@Service
public class IdentityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolutionService.class);

    private final CanonicalTrackRepository canonicalTrackRepository;
    private final PlatformAdapter primaryAdapter;

    public IdentityResolutionService(CanonicalTrackRepository canonicalTrackRepository,
                                     List<PlatformAdapter> adapters) {
        this.canonicalTrackRepository = canonicalTrackRepository;
        // 身份解析用第一个可用平台(后续可改为"按平台优先级"策略)
        this.primaryAdapter = adapters.isEmpty() ? null : adapters.get(0);
    }

    /** 解析结果:身份 + 置信度 + 判定版本类型 + 依据 */
    public record Resolution(
            CanonicalTrack track,
            Confidence confidence,
            VersionType judgedVersionType,
            List<String> reasons
    ) {
    }

    /**
     * 解析一个歌单条目的真实身份。
     *
     * @param rawTitle  原始歌名
     * @param rawArtist 原始歌手
     * @return 解析结果;无法解析(无适配器或无候选)时 track 为 null
     */
    public Resolution resolve(String rawTitle, String rawArtist) {
        if (primaryAdapter == null) {
            log.warn("无可用平台适配器,跳过身份解析");
            return new Resolution(null, Confidence.LOW, VersionType.OTHER, List.of());
        }

        String normTitle = TextNormalizer.normalizeTitle(rawTitle);
        String normArtist = TextNormalizer.normalizeArtist(rawArtist);
        if (normTitle.isEmpty()) {
            return new Resolution(null, Confidence.LOW, VersionType.OTHER, List.of("歌名为空"));
        }

        // 1. 先查缓存:已存在相同身份直接复用
        Optional<CanonicalTrack> existing = canonicalTrackRepository
                .findByNormalizedTitleAndNormalizedArtist(normTitle, normArtist);
        if (existing.isPresent()) {
            return new Resolution(existing.get(), Confidence.HIGH, VersionType.ORIGINAL,
                    List.of("命中本地身份库"));
        }

        // 2. 平台搜索候选(风控时抛异常,区别于"无此歌")
        List<TrackCandidate> candidates;
        try {
            candidates = primaryAdapter.search(rawTitle, rawArtist);
        } catch (RuntimeException e) {
            log.warn("平台风控/异常,跳过身份解析 {} - {}: {}", rawTitle, rawArtist, e.getMessage());
            return new Resolution(null, Confidence.LOW, VersionType.OTHER,
                    List.of("平台限流,请稍后重试"));
        }
        if (candidates.isEmpty()) {
            return new Resolution(null, Confidence.LOW, VersionType.OTHER,
                    List.of("平台搜索无结果"));
        }

        // 3. 打分,取最高分候选
        VersionScorer.ScoreResult best = null;
        TrackCandidate bestCandidate = null;
        for (TrackCandidate c : candidates) {
            VersionScorer.ScoreResult r = VersionScorer.score(
                    c.title(), c.artist(), c.album(), c.durationSec(), c.popularity(),
                    rawTitle, rawArtist, null);
            if (best == null || r.score() > best.score()) {
                best = r;
                bestCandidate = c;
            }
        }

        if (best == null || best.score() < VersionScorer.MEDIUM_THRESHOLD) {
            // 低置信:不自动创建身份,交给用户兜底
            return new Resolution(null, Confidence.LOW, best == null ? VersionType.OTHER : best.versionType(),
                    best == null ? List.of("无候选匹配") : best.reasons());
        }

        // 4. 生成真实身份(规范化歌名/歌手为准,平台候选仅作佐证)
        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle(normTitle)
                .normalizedArtist(normArtist)
                .build();
        CanonicalTrack saved = canonicalTrackRepository.save(track);
        log.info("身份解析成功: {} - {} (score={}, confidence={})",
                rawTitle, rawArtist, best.score(), best.confidence());
        return new Resolution(saved, best.confidence(), best.versionType(), best.reasons());
    }

    /** 查询已保存的真实身份(按规范化键) */
    public Optional<CanonicalTrack> findByIdentity(String normalizedTitle, String normalizedArtist) {
        return canonicalTrackRepository.findByNormalizedTitleAndNormalizedArtist(normalizedTitle, normalizedArtist);
    }
}
