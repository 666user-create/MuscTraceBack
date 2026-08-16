package org.example.musictraceback.adapter.kugou;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import tools.jackson.databind.ObjectMapper;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;
import org.example.musictraceback.infrastructure.http.HttpFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KugouAdapter 测试(MockWebServer 离线模拟,不依赖真实网络)。
 */
class KugouAdapterTests {

    private MockWebServer server;
    private KugouAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new KugouAdapter(
                new HttpFetcher(0, 0),
                new ObjectMapper(),
                server.url("/").toString(),
                "Mozilla/5.0 test"
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void parsePlaylist_parsesFilename() throws Exception {
        // 酷狗歌单接口:歌手+歌名合并于 filename
        String body = """
                {"data":{"total":2,"info":[
                  {"hash":"H1","filename":"周杰伦 - 晴天","duration":269},
                  {"hash":"H2","filename":"周杰伦 - 七里香","duration":300}
                ]}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<PlaylistEntryRaw> entries = adapter.parsePlaylist("https://www.kugou.com/special/single/137775.html");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).title()).isEqualTo("晴天");
        assertThat(entries.get(0).artist()).isEqualTo("周杰伦");
        assertThat(entries.get(0).platformTrackId()).isEqualTo("H1");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("specialid=137775");
    }

    @Test
    void parsePlaylist_invalidUrl_throws() {
        assertThatThrownBy(() -> adapter.parsePlaylist("https://example.com/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法从链接中识别酷狗");
    }

    @Test
    void search_returnsCandidates() throws Exception {
        String body = """
                {"status":1,"errcode":0,"data":{"info":[
                  {"hash":"H1","songname":"晴天","singername":"周杰伦","album_name":"叶惠美","duration":269,"ownercount":1000},
                  {"hash":"H2","songname":"晴天","singername":"某翻唱者","album_name":"cover","duration":240,"ownercount":50}
                ]}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<TrackCandidate> songs = adapter.search("晴天", "周杰伦");

        assertThat(songs).hasSize(2);
        assertThat(songs.get(0).title()).isEqualTo("晴天");
        assertThat(songs.get(0).artist()).isEqualTo("周杰伦");
        assertThat(songs.get(0).popularity()).isEqualTo(1000L);
        assertThat(songs.get(0).playUrl()).contains("H1");
    }

    @Test
    void search_fallbackToFilename_whenSingernameMissing() throws Exception {
        // 酷狗搜索偶发 singername 缺失,应从 filename 兜底
        String body = """
                {"data":{"info":[
                  {"hash":"H1","songname":"","singername":"","filename":"周杰伦 - 晴天","duration":269}
                ]}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<TrackCandidate> songs = adapter.search("晴天", "周杰伦");

        assertThat(songs).hasSize(1);
        assertThat(songs.get(0).title()).isEqualTo("晴天");
        assertThat(songs.get(0).artist()).isEqualTo("周杰伦");
    }

    @Test
    void search_httpError_returnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(adapter.search("晴天", "周杰伦")).isEmpty();
    }

    @Test
    void extractSpecialId_variants() {
        assertThat(KugouAdapter.extractSpecialId("137775")).isEqualTo("137775");
        assertThat(KugouAdapter.extractSpecialId("https://www.kugou.com/special/single/137775.html"))
                .isEqualTo("137775");
        assertThat(KugouAdapter.extractSpecialId("https://www.kugou.com/playlist/137775.html"))
                .isEqualTo("137775");
        assertThat(KugouAdapter.extractSpecialId("https://example.com/x")).isNull();
    }

    @Test
    void detail_returnsNull_fallback() {
        assertThat(adapter.detail("H1")).isNull();
    }
}
