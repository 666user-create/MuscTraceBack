package org.example.musictraceback;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.infrastructure.repository.CanonicalTrackRepository;
import org.example.musictraceback.infrastructure.repository.PlatformVersionRepository;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.example.musictraceback.infrastructure.repository.UserConfirmationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 歌单 REST 接口返回测试(异步架构)。
 * 使用 mock 适配器,不依赖真实网络;注入后等待后台异步处理完成再断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaylistApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private PlaylistEntryRepository playlistEntryRepository;

    @Autowired
    private CanonicalTrackRepository canonicalTrackRepository;

    @Autowired
    private PlatformVersionRepository platformVersionRepository;

    @Autowired
    private UserConfirmationRepository userConfirmationRepository;

    @Autowired
    private org.example.musictraceback.infrastructure.cache.SearchCache searchCache;

    @MockitoBean(name = "netEaseAdapter")
    private PlatformAdapter neteaseAdapter;

    @MockitoBean(name = "QQMusicAdapter")
    private PlatformAdapter qqAdapter;

    @MockitoBean(name = "kugouAdapter")
    private PlatformAdapter kugouAdapter;

    @BeforeEach
    void setUp() {
        when(neteaseAdapter.platformId()).thenReturn("netease");
        when(qqAdapter.platformId()).thenReturn("qq");
        when(qqAdapter.search(any(), any())).thenReturn(List.of());
        when(kugouAdapter.platformId()).thenReturn("kugou");
        when(kugouAdapter.search(any(), any())).thenReturn(List.of());
    }

    @BeforeEach
    void cleanDatabase() {
        userConfirmationRepository.deleteAll();
        platformVersionRepository.deleteAll();
        playlistEntryRepository.deleteAll();
        canonicalTrackRepository.deleteAll();
        snapshotRepository.deleteAll();
        searchCache.clear();
    }

    /** 等待异步处理完成(单线程执行器,一般秒级) */
    private long awaitCompleted(String importBody) {
        long playlistId = extractId(importBody);
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            String body = mockMvc.perform(get("/api/playlists/" + playlistId + "/progress"))
                    .andReturn().getResponse().getContentAsString();
            return body.contains("\"status\":\"COMPLETED\"");
        });
        return playlistId;
    }

    @Test
    void importPlaylist_returnsProcessingThenCompletes() throws Exception {
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.platform").value("netease"))
                .andExpect(jsonPath("$.entryCount").value(1))
                .andReturn().getResponse().getContentAsString();

        long playlistId = awaitCompleted(body);
        // 完成后进度 100%
        mockMvc.perform(get("/api/playlists/" + playlistId + "/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.percent").value(100));
    }

    @Test
    void importPlaylist_emptyUrl_badRequest() throws Exception {
        mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importPlaylist_unsupportedPlatform_badRequest() throws Exception {
        mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("无法识别歌单平台")));
    }

    @Test
    void importThenQuery_detailAndTrackEndpoints() throws Exception {
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = awaitCompleted(body);

        // 本地建档:身份 HIGH + NORMAL
        String detailBody = mockMvc.perform(get("/api/playlists/" + playlistId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].title").value("晴天"))
                .andExpect(jsonPath("$.entries[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.entries[0].confidence").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        long entryId = extractFirstEntryId(detailBody);

        // 单曲详情:来源平台版本零请求入库
        mockMvc.perform(get("/api/playlists/" + playlistId + "/tracks/" + entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("晴天"))
                .andExpect(jsonPath("$.versions[0].platform").value("netease"))
                .andExpect(jsonPath("$.versions[0].title").value("晴天"))
                .andExpect(jsonPath("$.versions[0].versionType").value("ORIGINAL"));
    }

    @Test
    void getPlaylist_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/playlists/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmTrack_updatesEntry() throws Exception {
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = awaitCompleted(body);

        String detailBody = mockMvc.perform(get("/api/playlists/" + playlistId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long entryId = extractFirstEntryId(detailBody);

        // 用户确认
        mockMvc.perform(post("/api/playlists/tracks/" + entryId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionType\":\"ORIGINAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionType").value("ORIGINAL"))
                .andExpect(jsonPath("$.playlistEntryId").value(entryId));
    }

    @Test
    void confirmTrack_missingVersionType_badRequest() throws Exception {
        mockMvc.perform(post("/api/playlists/tracks/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReport_returnsHealthStats() throws Exception {
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美"),
                new PlaylistEntryRaw("1002", "找不到的歌", "某歌手", "某专辑")));

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = awaitCompleted(body);

        // 本地建档:两首都是 HIGH + NORMAL(零请求,不再依赖搜索判定下架)
        mockMvc.perform(get("/api/playlists/" + playlistId + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCounts.TOTAL").value(2))
                .andExpect(jsonPath("$.statusCounts.NORMAL").value(2))
                .andExpect(jsonPath("$.healthScore").value(100))
                .andExpect(jsonPath("$.issues.length()").value(0));
    }

    @Test
    void getReport_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/playlists/99999/report"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getProgress_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/playlists/99999/progress"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resume_completed_returnsProcessing() throws Exception {
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));
        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = awaitCompleted(body);

        mockMvc.perform(post("/api/playlists/" + playlistId + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    private long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 从歌单详情响应中提取第一条目 id */
    private long extractFirstEntryId(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"entries\":\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("无法从响应提取 entryId: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
