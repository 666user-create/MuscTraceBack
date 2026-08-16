package org.example.musictraceback.domain.identity;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 字符串规范化(设计文档 9 节第 1 层)。
 * 目的:把"晴天 (Live)"、"晴天(feat. xxx)"、"晴天 " 等归一到同一形式,
 * 以便与平台搜索结果做身份匹配。
 *
 * 注意:完整繁简转换依赖字典,这里提供常用字映射 + 纯函数设计,
 * 后续可平滑替换为 OpenCC 类库。
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /** 括号内容(中英文括号、书名号),如 (Live)、(伴奏)、[Demo] */
    private static final Pattern BRACKETS = Pattern.compile("[（(\\[【].*?[）)\\]】]");

    /** feat./ft. 及其后内容 */
    private static final Pattern FEAT = Pattern.compile("(?i)(\\s*(feat|ft|featuring)[.．]?.*)$");

    /** 多余空白 */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 尾部脏字符(网易云搜索结果常见的 "周杰伦." / "周杰伦-" / "周杰伦_xx") */
    private static final Pattern TRAILING_JUNK = Pattern.compile("[.．·\\-—_\\s]+$");

    /** 尾部 feat./ft. 等标注 */
    private static final Pattern TRAILING_FEAT = Pattern.compile("(?i)(\\s*[-—·]\\s*(feat|ft)[.．]?.*)$");

    /** 常用简体字映射(示例级,覆盖高频字;完整字典后续接入) */
    private static final String SIMPLIFIED = "乐车见风门马龙东长与为发国说时会面无见后开前从";
    private static final String TRADITIONAL = "樂車見風門馬龍東長與為發國說時會麵無見後開前從";

    /**
     * 规范化歌名:去括号修饰 → 去 feat. → 繁转简 → 全角转半角 → 去空白 → 统一小写
     */
    public static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        s = BRACKETS.matcher(s).replaceAll("");
        s = FEAT.matcher(s).replaceAll("");
        s = traditionalToSimplified(s);
        s = fullWidthToHalfWidth(s);
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.toLowerCase();
    }

    /**
     * 规范化歌手:取主歌手(去 feat. 及多歌手分隔) → 繁转简 → 全角转半角 → 去尾部脏字符 → 去空白 → 统一小写
     */
    public static String normalizeArtist(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        // 多歌手只保留第一个(主歌手)
        int sep = firstIndexAny(s, "/", "、", "&", ",", "，", "·", "•");
        if (sep > 0) {
            s = s.substring(0, sep);
        }
        s = TRAILING_FEAT.matcher(s).replaceAll("");
        s = FEAT.matcher(s).replaceAll("");
        s = traditionalToSimplified(s);
        s = fullWidthToHalfWidth(s);
        s = TRAILING_JUNK.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.toLowerCase();
    }

    /** 首个分隔符位置,找不到返回 -1 */
    private static int firstIndexAny(String s, String... seps) {
        int min = -1;
        for (String sep : seps) {
            int i = s.indexOf(sep);
            if (i >= 0 && (min < 0 || i < min)) {
                min = i;
            }
        }
        return min;
    }

    /** 全角转半角 */
    static String fullWidthToHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 常用字繁转简(逐字符映射) */
    static String traditionalToSimplified(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            int idx = TRADITIONAL.indexOf(c);
            sb.append(idx >= 0 ? SIMPLIFIED.charAt(idx) : c);
        }
        return sb.toString();
    }

    /** 组合规范化:歌名+歌手 → 统一身份键 */
    public static String identityKey(String title, String artist) {
        return normalizeTitle(title) + "::" + normalizeArtist(artist);
    }
}
