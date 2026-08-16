package org.example.musictraceback.domain.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextNormalizer 测试:规范化歌名/歌手/身份键
 */
class TextNormalizerTests {

    @Test
    void normalizeTitle_stripsBrackets() {
        assertThat(TextNormalizer.normalizeTitle("晴天 (Live)")).isEqualTo("晴天");
        assertThat(TextNormalizer.normalizeTitle("晴天（伴奏）")).isEqualTo("晴天");
        assertThat(TextNormalizer.normalizeTitle("晴天 [Demo]")).isEqualTo("晴天");
        assertThat(TextNormalizer.normalizeTitle("晴天(正式版)")).isEqualTo("晴天");
    }

    @Test
    void normalizeTitle_stripsFeat() {
        assertThat(TextNormalizer.normalizeTitle("危险 feat. xxx")).isEqualTo("危险");
        assertThat(TextNormalizer.normalizeTitle("危险 ft.xxx")).isEqualTo("危险");
    }

    @Test
    void normalizeTitle_fullWidthAndSpaces() {
        assertThat(TextNormalizer.normalizeTitle("  晴天  ")).isEqualTo("晴天");
        assertThat(TextNormalizer.normalizeTitle("ＡＢＣ")).isEqualTo("abc");
    }

    @Test
    void normalizeTitle_traditionalToSimplified() {
        assertThat(TextNormalizer.normalizeTitle("晴天")).isEqualTo("晴天");
        // 樂 → 乐、長 → 长、與 → 与(示例映射表覆盖的常用字)
        assertThat(TextNormalizer.normalizeTitle("音樂")).isEqualTo("音乐");
        assertThat(TextNormalizer.normalizeTitle("長樂與愁")).isEqualTo("长乐与愁");
    }

    @Test
    void normalizeArtist_keepsMainArtistOnly() {
        assertThat(TextNormalizer.normalizeArtist("周杰伦 / 蔡依林")).isEqualTo("周杰伦");
        assertThat(TextNormalizer.normalizeArtist("周杰伦、方文山")).isEqualTo("周杰伦");
        assertThat(TextNormalizer.normalizeArtist("周杰伦 feat. 蔡依林")).isEqualTo("周杰伦");
    }

    @Test
    void normalizeArtist_stripsTrailingJunk() {
        // 网易云搜索结果常见的脏字符
        assertThat(TextNormalizer.normalizeArtist("周杰伦.")).isEqualTo("周杰伦");
        assertThat(TextNormalizer.normalizeArtist("周杰伦-")).isEqualTo("周杰伦");
        assertThat(TextNormalizer.normalizeArtist("周杰伦_官方")).isEqualTo("周杰伦_官方");
        assertThat(TextNormalizer.normalizeArtist("周杰伦 · 蔡依林")).isEqualTo("周杰伦");
    }

    @Test
    void identityKey_combinesNormalized() {
        assertThat(TextNormalizer.identityKey("晴天 (Live)", "周杰伦"))
                .isEqualTo(TextNormalizer.identityKey("晴天", "周杰伦"));
        // 不同身份键不同
        assertThat(TextNormalizer.identityKey("晴天", "周杰伦"))
                .isNotEqualTo(TextNormalizer.identityKey("晴天", "五月天"));
    }

    @Test
    void nullInput_returnsEmpty() {
        assertThat(TextNormalizer.normalizeTitle(null)).isEmpty();
        assertThat(TextNormalizer.normalizeArtist(null)).isEmpty();
    }
}
