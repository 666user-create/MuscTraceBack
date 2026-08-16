package org.example.musictraceback.adapter.netease;

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
 * 网易云音乐适配器。
 *
 * 接口(均为 HTTP,基础地址可配置,见 application.properties):
 *  - 歌单详情: GET {base}/api/v6/playlist/detail?id={id}&n=1000
 *  - 搜索:     GET {base}/api/search/get/web?s={keyword}&type=1&limit={n}
 *  - 歌曲详情: GET {base}/api/v3/song/detail?c=[{"id":{id}}]
 *
 * 注意:歌单接口返回的歌曲字段是 ar(艺术家)/al(专辑)/dt(时长毫秒),
 * 搜索接口返回的是 artists/album/duration —— 两套字段名,解析时分别处理。
 */
@Component
public class NetEaseAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(NetEaseAdapter.class);
    private static final Pattern PLAYLIST_ID = Pattern.compile("[?&]id=(\\d+)");

    private final HttpFetcher http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String userAgent;

    public NetEaseAdapter(HttpFetcher http,
                          ObjectMapper mapper,
                          @Value("${music.netease.base-url:http://music.163.com}") String baseUrl,
                          @Value("${music.netease.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36}") String userAgent) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.userAgent = userAgent;
    }

    @Override
    public String platformId() {
        return "netease";
    }

    @Override
    public List<PlaylistEntryRaw> parsePlaylist(String shareUrlOrId) {
        String playlistId = extractPlaylistId(shareUrlOrId);
        if (playlistId == null) {
            throw new IllegalArgumentException("无法从链接中识别网易云歌单 ID: " + shareUrlOrId);
        }
        String url = baseUrl + "/api/v6/playlist/detail?id=" + playlistId + "&n=1000";
        try {
            String body = http.get(url, userAgent);
            JsonNode root = mapper.readTree(body);
            JsonNode playlist = root.path("playlist");
            JsonNode tracks = playlist.path("tracks");
            List<PlaylistEntryRaw> result = new ArrayList<>();
            for (JsonNode t : tracks) {
                result.add(new PlaylistEntryRaw(
                        t.path("id").asText(),
                        t.path("name").asText(""),
                        artistsName(t.path("ar")),
                        t.path("al").path("name").asText("")
                ));
            }
            log.info("网易云歌单 {} 解析到 {} 首", playlistId, result.size());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("网易云歌单解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TrackCandidate> search(String title, String artist) {
        String keyword = (title + " " + artist).trim();
        String url = baseUrl + "/api/search/get/web?s="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&type=1&limit=20";
        try {
            String body = http.get(url, userAgent);
            JsonNode songs = mapper.readTree(body).path("result").path("songs");
            List<TrackCandidate> result = new ArrayList<>();
            for (JsonNode s : songs) {
                result.add(toCandidate(s));
            }
            return result;
        } catch (HttpFetcher.RateLimitedException e) {
            // 风控:向上抛,由调用方降级(区别于"无此歌")
            throw e;
        } catch (Exception e) {
            log.warn("网易云搜索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    @Override
    public TrackCandidate detail(String trackId) {
        String url = baseUrl + "/api/v3/song/detail?c="
                + URLEncoder.encode("[{\"id\":" + trackId + "}]", StandardCharsets.UTF_8);
        try {
            String body = http.get(url, userAgent);
            JsonNode songs = mapper.readTree(body).path("songs");
            if (songs.isEmpty()) {
                return null;
            }
            return toCandidate(songs.get(0));
        } catch (Exception e) {
            log.warn("网易云歌曲详情失败 id={}: {}", trackId, e.getMessage());
            return null;
        }
    }

    @Override
    public String playUrl(String trackId) {
        return "https://music.163.com/#/song?id=" + trackId;
    }

    /** 搜索/详情接口的歌曲字段:artists/album/duration */
    private TrackCandidate toCandidate(JsonNode s) {
        return new TrackCandidate(
                s.path("id").asText(),
                s.path("name").asText(""),
                artistsName(s.path("artists")),
                s.path("album").path("name").asText(""),
                secondsOf(s.path("duration").asLong(0)),
                s.path("pop").asLong(0),
                playUrl(s.path("id").asText())
        );
    }

    /** 歌单接口的歌曲字段:ar = artists 数组 */
    private static String artistsName(JsonNode ar) {
        List<String> names = new ArrayList<>();
        for (JsonNode a : ar) {
            String name = a.path("name").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    /** 毫秒 → 秒 */
    private static Integer secondsOf(long millis) {
        return millis > 0 ? (int) (millis / 1000) : null;
    }

    /** 从分享链接提取歌单 ID(支持 ?id=xxx 与纯数字) */
    static String extractPlaylistId(String shareUrlOrId) {
        if (shareUrlOrId == null || shareUrlOrId.isBlank()) {
            return null;
        }
        String s = shareUrlOrId.trim();
        if (s.matches("\\d+")) {
            return s;
        }
        Matcher m = PLAYLIST_ID.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
