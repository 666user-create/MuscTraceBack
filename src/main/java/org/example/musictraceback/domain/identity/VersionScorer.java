package org.example.musictraceback.domain.identity;

import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 版本打分规则引擎(设计文档 9 节第 2 层)。
 *
 * 对每个候选版本收集证据加权求和:
 *  歌手一致 +40 / 标题一致 +30 / 时长接近(差<10s)+10 / 热度弱佐证 +5
 *  翻唱关键词 -30(Live/翻唱/cover/remix/伴奏…)
 * 分数映射:≥80 HIGH / 40-79 MEDIUM / <40 LOW。
 *
 * 纯 Java、无框架依赖、无机器学习 —— 全部规则集中于此,便于调参。
 */
public final class VersionScorer {

    /** 权重常量(集中配置,便于调参) */
    public static final int ARTIST_MATCH = 45;
    public static final int TITLE_MATCH = 35;
    public static final int DURATION_CLOSE = 10;
    public static final int POPULARITY_HINT = 5;
    public static final int COVER_PENALTY = 30;
    public static final int DURATION_TOLERANCE_SEC = 10;
    public static final int HIGH_THRESHOLD = 80;
    public static final int MEDIUM_THRESHOLD = 40;

    /** 非原版关键词(标题或专辑命中即判为对应类型) */
    private static final String[] LIVE_KEYWORDS = {"live", "现场", "演唱会"};
    private static final String[] COVER_KEYWORDS = {"翻唱", "cover", "致敬", "tribute"};
    private static final String[] REMIX_KEYWORDS = {"remix", "混音", "重混"};
    private static final String[] INSTRUMENTAL_KEYWORDS = {"伴奏", "纯音乐", "instrumental", "karaoke", "卡拉ok"};

    private VersionScorer() {
    }

    /** 打分结果 */
    public record ScoreResult(
            int score,
            VersionType versionType,
            Confidence confidence,
            List<String> reasons
    ) {
    }

    /**
     * 对候选版本打分。
     *
     * @param candidateTitle   候选歌名(原始)
     * @param candidateArtist  候选歌手(原始,可含多歌手 "/")
     * @param candidateAlbum   候选专辑(可空)
     * @param candidateDurationSec 候选时长(秒,可空)
     * @param candidatePopularity   候选热度(可空)
     * @param targetTitle      目标真实身份歌名(原始)
     * @param targetArtist     目标真实身份歌手(原始)
     * @param targetDurationSec     目标主流版本时长(秒,可空,用于时长比较)
     */
    public static ScoreResult score(String candidateTitle,
                                    String candidateArtist,
                                    String candidateAlbum,
                                    Integer candidateDurationSec,
                                    Long candidatePopularity,
                                    String targetTitle,
                                    String targetArtist,
                                    Integer targetDurationSec) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        VersionType versionType = classifyVersion(candidateTitle, candidateAlbum);

        // 证据 1:歌手一致(最强证据)
        String normCandidate = TextNormalizer.normalizeArtist(candidateArtist);
        String normTarget = TextNormalizer.normalizeArtist(targetArtist);
        if (normCandidate.equals(normTarget)) {
            score += ARTIST_MATCH;
            reasons.add("歌手一致");
        } else if (normCandidate.contains(normTarget) || normTarget.contains(normCandidate)) {
            score += ARTIST_MATCH / 2;
            reasons.add("歌手部分匹配");
        } else {
            // 歌手完全不一致 → 大概率是翻唱/同名他人,直接判 COVER 并大幅降分
            score -= COVER_PENALTY;
            versionType = VersionType.COVER;
            reasons.add("歌手不一致,疑似翻唱或同名他人");
        }

        // 证据 2:标题一致
        String normCTitle = TextNormalizer.normalizeTitle(candidateTitle);
        String normTTitle = TextNormalizer.normalizeTitle(targetTitle);
        if (normCTitle.equals(normTTitle)) {
            score += TITLE_MATCH;
            reasons.add("标题一致");
        } else if (normCTitle.contains(normTTitle) || normTTitle.contains(normCTitle)) {
            score += TITLE_MATCH / 2;
            reasons.add("标题部分匹配");
        }

        // 证据 3:翻唱类关键词降权(仅当歌手已不一致时更强,这里统一处理)
        if (versionType != VersionType.ORIGINAL) {
            score -= COVER_PENALTY;
            reasons.add("标题/专辑含非原版关键词(" + versionType + ")");
        }

        // 证据 4:时长接近(需两侧都有时长)
        if (candidateDurationSec != null && targetDurationSec != null && targetDurationSec > 0) {
            int diff = Math.abs(candidateDurationSec - targetDurationSec);
            if (diff <= DURATION_TOLERANCE_SEC) {
                score += DURATION_CLOSE;
                reasons.add("时长接近(差 " + diff + "s)");
            }
        }

        // 证据 5:热度弱佐证(仅加分不扣分)
        if (candidatePopularity != null && candidatePopularity >= 90) {
            score += POPULARITY_HINT;
            reasons.add("平台热度高");
        }

        // 置信度映射
        Confidence confidence = score >= HIGH_THRESHOLD ? Confidence.HIGH
                : score >= MEDIUM_THRESHOLD ? Confidence.MEDIUM
                : Confidence.LOW;

        return new ScoreResult(Math.max(0, score), versionType, confidence, reasons);
    }

    /** 依据标题/专辑关键词判定版本类型,默认 ORIGINAL */
    public static VersionType classifyVersion(String title, String album) {
        String haystack = ((title == null ? "" : title) + " " + (album == null ? "" : album))
                .toLowerCase(Locale.ROOT);
        if (containsAny(haystack, INSTRUMENTAL_KEYWORDS)) {
            return VersionType.INSTRUMENTAL;
        }
        if (containsAny(haystack, REMIX_KEYWORDS)) {
            return VersionType.REMIX;
        }
        if (containsAny(haystack, LIVE_KEYWORDS)) {
            return VersionType.LIVE;
        }
        if (containsAny(haystack, COVER_KEYWORDS)) {
            return VersionType.COVER;
        }
        return VersionType.ORIGINAL;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
