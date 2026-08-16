package org.example.musictraceback.adapter.kugou;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.infrastructure.http.HttpFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酷狗音乐适配器。
 *
 * 接口(均为 HTTP,基础地址可配置):
 *  - 歌单歌曲: GET {base}/api/v3/special/song?specialid={id}&page=1&pagesize=1000&format=json
 *  - 搜索:     GET {base}/api/v3/search/song?format=json&keyword={keyword}&page=1&pagesize=20
 *
 * 字段差异说明:
 *  - 搜索接口: songname/singername/album_name/duration(秒)/ownercount(热度)/hash
 *  - 歌单接口: 无 songname/singername,歌手和歌名合并在 filename("歌手 - 歌名")中,需解析
 *  - 热度字段 ownercount(拥有该歌的用户数),近似热度
 */
@Component
public class KugouAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(KugouAdapter.class);
    private static final Pattern SPECIAL_ID = Pattern.compile("[?&]specialid=(\\d+)");
    private static final Pattern FILENAME_SPLIT = Pattern.compile("^(.+?)\\s*-\\s*(.+)$");

    private final HttpFetcher http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String userAgent;

    public KugouAdapter(HttpFetcher http,
                        ObjectMapper mapper,
                        @Value("${music.kugou.base-url:http://mobilecdn.kugou.com}") String baseUrl,
                        @Value("${music.kugou.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36}") String userAgent) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.userAgent = userAgent;
    }

    @Override
    public String platformId() {
        return "kugou";
    }

    @Override
    public List<PlaylistEntryRaw> parsePlaylist(String shareUrlOrId) {
        String specialId = extractSpecialId(shareUrlOrId);
        if (specialId == null) {
            throw new IllegalArgumentException("无法从链接中识别酷狗歌单 ID: " + shareUrlOrId);
        }
        String url = baseUrl + "/api/v3/special/song?specialid=" + specialId
                + "&page=1&pagesize=1000&format=json";
        try {
            String body = http.get(url, userAgent);
            JsonNode infos = mapper.readTree(body).path("data").path("info");
            List<PlaylistEntryRaw> result = new ArrayList<>();
            for (JsonNode s : infos) {
                // 歌单接口:歌手+歌名在 filename("歌手 - 歌名")
                String filename = s.path("filename").asText("");
                String[] parts = splitFilename(filename);
                result.add(new PlaylistEntryRaw(
                        s.path("hash").asText(),
                        parts[1],
                        parts[0],
                        ""
                ));
            }
            log.info("酷狗歌单 {} 解析到 {} 首", specialId, result.size());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("酷狗歌单解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TrackCandidate> search(String title, String artist) {
        String keyword = (title + " " + artist).trim();
        String url = baseUrl + "/api/v3/search/song?format=json&keyword="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&page=1&pagesize=20";
        try {
            String body = http.get(url, userAgent);
            JsonNode infos = mapper.readTree(body).path("data").path("info");
            List<TrackCandidate> result = new ArrayList<>();
            for (JsonNode s : infos) {
                String singername = s.path("singername").asText("");
                String songname = s.path("songname").asText("");
                // 搜索接口偶发 singername 缺失,从 filename 兜底
                if (singername.isEmpty() || songname.isEmpty()) {
                    String[] parts = splitFilename(s.path("filename").asText(""));
                    if (singername.isEmpty()) singername = parts[0];
                    if (songname.isEmpty()) songname = parts[1];
                }
                result.add(new TrackCandidate(
                        s.path("hash").asText(),
                        songname,
                        singername,
                        s.path("album_name").asText(""),
                        s.path("duration").isNumber() ? s.path("duration").asInt() : null,
                        s.path("ownercount").asLong(0),
                        playUrl(s.path("hash").asText())
                ));
            }
            return result;
        } catch (HttpFetcher.RateLimitedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("酷狗搜索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    @Override
    public TrackCandidate detail(String trackId) {
        // 酷狗单曲详情接口不稳定,走降级
        return null;
    }

    @Override
    public String playUrl(String trackId) {
        return "https://www.kugou.com/song/#hash=" + trackId;
    }

    /** "歌手 - 歌名" → [歌手, 歌名];无分隔符时按 [原样, 原样] */
    private static String[] splitFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return new String[]{"", ""};
        }
        Matcher m = FILENAME_SPLIT.matcher(filename.trim());
        if (m.matches()) {
            return new String[]{m.group(1).trim(), m.group(2).trim()};
        }
        return new String[]{filename.trim(), filename.trim()};
    }

    /** 从分享链接提取歌单 specialid */
    static String extractSpecialId(String shareUrlOrId) {
        if (shareUrlOrId == null || shareUrlOrId.isBlank()) {
            return null;
        }
        String s = shareUrlOrId.trim();
        if (s.matches("\\d+")) {
            return s;
        }
        Matcher m = SPECIAL_ID.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        // https://www.kugou.com/special/single/137775.html 或 /playlist/137775.html
        Matcher path = Pattern.compile("/(?:special/single|playlist)/(\\d+)").matcher(s);
        return path.find() ? path.group(1) : null;
    }
}
