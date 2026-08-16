package org.example.musictraceback.domain.identity;

import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VersionScorer 测试:证据组合 → 分数/类型/置信度
 */
class VersionScorerTests {

    private static final String TITLE = "晴天";
    private static final String ARTIST = "周杰伦";
    private static final Integer DURATION = 269;

    @Test
    void originalVersion_scoresHigh() {
        var r = VersionScorer.score("晴天", "周杰伦", "叶惠美", 269, 100L,
                TITLE, ARTIST, DURATION);
        assertThat(r.score()).isGreaterThanOrEqualTo(VersionScorer.HIGH_THRESHOLD);
        assertThat(r.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(r.versionType()).isEqualTo(VersionType.ORIGINAL);
    }

    @Test
    void liveVersion_detectedAndPenalized() {
        var r = VersionScorer.score("晴天 (Live)", "周杰伦", "现场专辑", 300, 80L,
                TITLE, ARTIST, DURATION);
        assertThat(r.versionType()).isEqualTo(VersionType.LIVE);
        assertThat(r.score()).isLessThan(VersionScorer.HIGH_THRESHOLD);
    }

    @Test
    void coverVersion_differentArtist_penalized() {
        var r = VersionScorer.score("晴天", "某翻唱者", "cover专辑", 200, 30L,
                TITLE, ARTIST, DURATION);
        assertThat(r.versionType()).isEqualTo(VersionType.COVER);
        assertThat(r.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void remixAndInstrumental_classified() {
        assertThat(VersionScorer.classifyVersion("晴天 Remix", null)).isEqualTo(VersionType.REMIX);
        assertThat(VersionScorer.classifyVersion("晴天", "伴奏版")).isEqualTo(VersionType.INSTRUMENTAL);
        assertThat(VersionScorer.classifyVersion("晴天", "叶惠美")).isEqualTo(VersionType.ORIGINAL);
    }

    @Test
    void durationClose_addsPoints() {
        var withDuration = VersionScorer.score("晴天", "周杰伦", "叶惠美", 268, null,
                TITLE, ARTIST, DURATION);
        var withoutDuration = VersionScorer.score("晴天", "周杰伦", "叶惠美", null, null,
                TITLE, ARTIST, DURATION);
        assertThat(withDuration.score()).isGreaterThan(withoutDuration.score());
    }

    @Test
    void popularityHint_addsPoints() {
        var hot = VersionScorer.score("晴天", "周杰伦", "叶惠美", null, 95L,
                TITLE, ARTIST, DURATION);
        var cold = VersionScorer.score("晴天", "周杰伦", "叶惠美", null, 10L,
                TITLE, ARTIST, DURATION);
        assertThat(hot.score()).isGreaterThan(cold.score());
    }

    @Test
    void score_neverNegative() {
        var r = VersionScorer.score("完全不同", "完全无关歌手", null, null, null,
                TITLE, ARTIST, DURATION);
        assertThat(r.score()).isGreaterThanOrEqualTo(0);
    }
}
