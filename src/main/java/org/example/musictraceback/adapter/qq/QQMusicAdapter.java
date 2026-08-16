package org.example.musictraceback.adapter.qq;

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
 * QQ 音乐适配器。
 *
 * 接口(均为 HTTP,基础地址可配置):
 *  - 歌单详情: GET {base}/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id={id}&format=json&newsong=1
 *  - 搜索:     GET {base}/soso/fcgi-bin/client_search_cp?p=1&n=20&w={keyword}&format=json
 *
 * 字段差异说明:
 *  - 搜索接口返回 songname/singer[].name/interval(秒)/albumname/songmid
 *  - 歌单接口返回 name/singer[].name/interval(秒)/album.name/mid
 *  - QQ 无公开热度字段,popularity 置 0(依赖时长/歌手/标题证据打分)
 */
@Component
public class QQMusicAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(QQMusicAdapter.class);
    private static final Pattern PLAYLIST_ID = Pattern.compile("[?&]id=(\\d+)");

    private final HttpFetcher http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String userAgent;

    public QQMusicAdapter(HttpFetcher http,
                          ObjectMapper mapper,
                          @Value("${music.qq.base-url:http://c.y.qq.com}") String baseUrl,
                          @Value("${music.qq.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36}") String userAgent) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.userAgent = userAgent;
    }

    @Override
    public String platformId() {
        return "qq";
    }

    @Override
    public List<PlaylistEntryRaw> parsePlaylist(String shareUrlOrId) {
        String playlistId = extractPlaylistId(shareUrlOrId);
        if (playlistId == null) {
            throw new IllegalArgumentException("无法从链接中识别 QQ 音乐歌单 ID: " + shareUrlOrId);
        }
        String url = baseUrl + "/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id=" + playlistId
                + "&format=json&newsong=1&platform=jqspaframe.json";
        try {
            String body = http.get(url, userAgent);
            JsonNode songs = mapper.readTree(body).path("data").path("cdlist").path(0).path("songlist");
            List<PlaylistEntryRaw> result = new ArrayList<>();
            for (JsonNode s : songs) {
                result.add(new PlaylistEntryRaw(
                        s.path("mid").asText(s.path("id").asText()),
                        s.path("name").asText(""),
                        singersName(s.path("singer")),
                        s.path("album").path("name").asText("")
                ));
            }
            log.info("QQ 音乐歌单 {} 解析到 {} 首", playlistId, result.size());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("QQ 音乐歌单解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TrackCandidate> search(String title, String artist) {
        String keyword = (title + " " + artist).trim();
        String url = baseUrl + "/soso/fcgi-bin/client_search_cp?p=1&n=20&format=json&w="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        try {
            String body = http.get(url, userAgent);
            JsonNode songs = mapper.readTree(body).path("data").path("song").path("list");
            List<TrackCandidate> result = new ArrayList<>();
            for (JsonNode s : songs) {
                result.add(new TrackCandidate(
                        s.path("songmid").asText(s.path("songid").asText()),
                        s.path("songname").asText(""),
                        singersName(s.path("singer")),
                        s.path("albumname").asText(""),
                        s.path("interval").isNumber() ? s.path("interval").asInt() : null,
                        0L, // QQ 无公开热度
                        playUrl(s.path("songmid").asText())
                ));
            }
            return result;
        } catch (HttpFetcher.RateLimitedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("QQ 音乐搜索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    @Override
    public TrackCandidate detail(String trackId) {
        // QQ 音乐单曲详情接口不稳定,直接返回 null 走降级(搜索已覆盖主要场景)
        return null;
    }

    @Override
    public String playUrl(String trackId) {
        return "https://y.qq.com/n/ryqq/songDetail/" + trackId;
    }

    private static String singersName(JsonNode singers) {
        List<String> names = new ArrayList<>();
        for (JsonNode s : singers) {
            String name = s.path("name").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    /** 从分享链接提取歌单 ID(支持 ?id=xxx、纯数字、y.qq.com/n/ryqq/playlist/xxx) */
    static String extractPlaylistId(String shareUrlOrId) {
        if (shareUrlOrId == null || shareUrlOrId.isBlank()) {
            return null;
        }
        String s = shareUrlOrId.trim();
        if (s.matches("\\d+")) {
            return s;
        }
        Matcher m = PLAYLIST_ID.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        // https://y.qq.com/n/ryqq/playlist/7408243256
        Matcher path = Pattern.compile("/playlist/(\\d+)").matcher(s);
        return path.find() ? path.group(1) : null;
    }
}
