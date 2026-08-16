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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 歌单 REST 接口返回测试(验证 JSON 契约,前端依赖这些字段)。
 * 使用 mock 适配器,不依赖真实网络。测试间隔离:每用例前清空数据。
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
        // 版本发现会遍历全部适配器;未注入的 QQ/酷狗默认返回空,避免真实网络
        when(qqAdapter.platformId()).thenReturn("qq");
        when(qqAdapter.search(any(), any())).thenReturn(List.of());
        when(kugouAdapter.platformId()).thenReturn("kugou");
        when(kugouAdapter.search(any(), any())).thenReturn(List.of());
    }

    @BeforeEach
    void cleanDatabase() {
        // @SpringBootTest 复用同一上下文,需手动清理避免唯一约束冲突
        // 删除顺序按外键依赖(子表先删):确认 → 版本 → 条目 → 身份 → 快照
        userConfirmationRepository.deleteAll();
        platformVersionRepository.deleteAll();
        playlistEntryRepository.deleteAll();
        canonicalTrackRepository.deleteAll();
        snapshotRepository.deleteAll();
        // 清空搜索缓存,避免测试间的空结果污染
        searchCache.clear();
    }

    @Test
    void importPlaylist_returnsSummary() throws Exception {
        when(neteaseAdapter.platformId()).thenReturn("netease");
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));
        // 无候选 → 低置信,不建身份(流程仍完成)
        when(neteaseAdapter.search(any(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.platform").value("netease"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.entryCount").value(1));
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
        when(neteaseAdapter.platformId()).thenReturn("netease");
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));
        when(neteaseAdapter.search("晴天", "周杰伦")).thenReturn(List.of(
                new TrackCandidate("2001", "晴天", "周杰伦", "叶惠美", 269, 100L, "https://x/2001")));
        when(neteaseAdapter.playUrl(any())).thenReturn("https://x/2001");

        // 注入
        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = extractId(body);

        // 歌单详情
        String detailBody = mockMvc.perform(get("/api/playlists/" + playlistId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].title").value("晴天"))
                .andExpect(jsonPath("$.entries[0].status").value("NORMAL"))
                .andExpect(jsonPath("$.entries[0].confidence").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        long entryId = extractFirstEntryId(detailBody);

        // 单曲详情(含 Top 版本)
        mockMvc.perform(get("/api/playlists/" + playlistId + "/tracks/" + entryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("晴天"))
                .andExpect(jsonPath("$.versions[0].platform").value("netease"))
                .andExpect(jsonPath("$.versions[0].title").value("晴天"))
                .andExpect(jsonPath("$.versions[0].versionType").value("ORIGINAL"))
                .andExpect(jsonPath("$.versions[0].playUrl").value("https://x/2001"));
    }

    @Test
    void getPlaylist_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/playlists/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void confirmTrack_updatesEntry() throws Exception {
        when(neteaseAdapter.platformId()).thenReturn("netease");
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美")));
        // 无候选 → 身份解析 LOW → DELISTED,供用户确认
        when(neteaseAdapter.search(any(), any())).thenReturn(List.of());

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = extractId(body);

        String detailBody = mockMvc.perform(get("/api/playlists/" + playlistId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].status").value("DELISTED"))
                .andExpect(jsonPath("$.entries[0].confidence").value("LOW"))
                .andReturn().getResponse().getContentAsString();
        long entryId = extractFirstEntryId(detailBody);

        // 用户确认:这是原版
        mockMvc.perform(post("/api/playlists/tracks/" + entryId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionType\":\"ORIGINAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionType").value("ORIGINAL"))
                .andExpect(jsonPath("$.playlistEntryId").value(entryId));

        // 确认后条目置信度提升为 HIGH
        mockMvc.perform(get("/api/playlists/" + playlistId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].confidence").value("HIGH"));
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
        when(neteaseAdapter.platformId()).thenReturn("netease");
        when(neteaseAdapter.parsePlaylist(anyString())).thenReturn(List.of(
                new PlaylistEntryRaw("1001", "晴天", "周杰伦", "叶惠美"),
                new PlaylistEntryRaw("1002", "找不到的歌", "某歌手", "某专辑")));
        // 第一首可解析,第二首无候选(下架)
        when(neteaseAdapter.search("晴天", "周杰伦")).thenReturn(List.of(
                new TrackCandidate("2001", "晴天", "周杰伦", "叶惠美", 269, 100L, "https://x/2001")));
        when(neteaseAdapter.search("找不到的歌", "某歌手")).thenReturn(List.of());
        when(neteaseAdapter.playUrl(any())).thenReturn("https://x/2001");

        String body = mockMvc.perform(post("/api/playlists/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://music.163.com/#/playlist?id=3778678\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long playlistId = extractId(body);

        mockMvc.perform(get("/api/playlists/" + playlistId + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCounts.TOTAL").value(2))
                .andExpect(jsonPath("$.statusCounts.NORMAL").value(1))
                .andExpect(jsonPath("$.statusCounts.DELISTED").value(1))
                .andExpect(jsonPath("$.healthScore").value(50))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].status").value("DELISTED"))
                .andExpect(jsonPath("$.issues[0].title").value("找不到的歌"));
    }

    @Test
    void getReport_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/playlists/99999/report"))
                .andExpect(status().isNotFound());
    }

    private long extractId(String json) {
        return Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    /** 从歌单详情响应中提取第一条目 id */
    private long extractFirstEntryId(String json) {
        // entries[0] 的 id 出现在 "entries":[{"id":N 处
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"entries\":\\s*\\[\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("无法从响应提取 entryId: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
