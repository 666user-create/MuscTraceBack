package org.example.musictraceback.adapter.qq;

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
 * QQMusicAdapter 测试(MockWebServer 离线模拟,不依赖真实网络)。
 */
class QQMusicAdapterTests {

    private MockWebServer server;
    private QQMusicAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new QQMusicAdapter(
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
    void parsePlaylist_returnsEntries() throws Exception {
        String body = """
                {"data":{"cdlist":[{"dissname":"我的歌单","songlist":[
                  {"mid":"000A","name":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"叶惠美"},"interval":269},
                  {"mid":"000B","name":"七里香","singer":[{"name":"周杰伦"},{"name":"蔡依林"}],"album":{"name":"七里香"},"interval":300}
                ]}]}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<PlaylistEntryRaw> entries = adapter.parsePlaylist("https://y.qq.com/n/ryqq/playlist/3506837410");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).title()).isEqualTo("晴天");
        assertThat(entries.get(0).artist()).isEqualTo("周杰伦");
        assertThat(entries.get(1).artist()).isEqualTo("周杰伦 / 蔡依林");
        assertThat(entries.get(0).platformTrackId()).isEqualTo("000A");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("id=3506837410");
    }

    @Test
    void parsePlaylist_invalidUrl_throws() {
        assertThatThrownBy(() -> adapter.parsePlaylist("https://example.com/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法从链接中识别 QQ");
    }

    @Test
    void search_returnsCandidates() throws Exception {
        String body = """
                {"code":0,"data":{"song":{"list":[
                  {"songmid":"MID1","songname":"晴天","singer":[{"name":"周杰伦"}],"albumname":"叶惠美","interval":269},
                  {"songmid":"MID2","songname":"晴天","singer":[{"name":"某翻唱者"}],"albumname":"cover","interval":240}
                ]}}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<TrackCandidate> songs = adapter.search("晴天", "周杰伦");

        assertThat(songs).hasSize(2);
        assertThat(songs.get(0).title()).isEqualTo("晴天");
        assertThat(songs.get(0).artist()).isEqualTo("周杰伦");
        assertThat(songs.get(0).durationSec()).isEqualTo(269);
        assertThat(songs.get(0).playUrl()).contains("MID1");
    }

    @Test
    void search_httpError_returnsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThat(adapter.search("晴天", "周杰伦")).isEmpty();
    }

    @Test
    void extractPlaylistId_variants() {
        assertThat(QQMusicAdapter.extractPlaylistId("3506837410")).isEqualTo("3506837410");
        assertThat(QQMusicAdapter.extractPlaylistId("https://y.qq.com/n/ryqq/playlist/3506837410"))
                .isEqualTo("3506837410");
        assertThat(QQMusicAdapter.extractPlaylistId("https://c.y.qq.com/base/fcgi-bin/u?__=abc&id=3506837410"))
                .isEqualTo("3506837410");
        assertThat(QQMusicAdapter.extractPlaylistId("  ")).isNull();
    }

    @Test
    void detail_returnsNull_fallback() {
        assertThat(adapter.detail("MID1")).isNull();
    }
}
