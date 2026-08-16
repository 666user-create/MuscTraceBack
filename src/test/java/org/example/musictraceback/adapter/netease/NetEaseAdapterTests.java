package org.example.musictraceback.adapter.netease;

import tools.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
 * NetEaseAdapter 测试:用 MockWebServer 离线模拟平台接口,不依赖真实网络。
 * 覆盖:歌单解析 / 搜索 / 详情 / ID 提取 / 异常路径。
 */
class NetEaseAdapterTests {

    private MockWebServer server;
    private NetEaseAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        adapter = new NetEaseAdapter(
                new HttpFetcher(0, 0), // 测试环境不限流,不退避
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
        // 模拟歌单详情响应(网易云真实结构:ar/al/dt)
        String body = """
                {"playlist":{"name":"热歌榜","tracks":[
                  {"id":1001,"name":"晴天","ar":[{"name":"周杰伦"}],"al":{"name":"叶惠美"},"dt":269000},
                  {"id":1002,"name":"七里香","ar":[{"name":"周杰伦"},{"name":"蔡依林"}],"al":{"name":"七里香"},"dt":300000}
                ]}}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<PlaylistEntryRaw> entries = adapter.parsePlaylist("https://music.163.com/#/playlist?id=3778678");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).title()).isEqualTo("晴天");
        assertThat(entries.get(0).artist()).isEqualTo("周杰伦");
        assertThat(entries.get(1).artist()).isEqualTo("周杰伦 / 蔡依林");
        assertThat(entries.get(0).album()).isEqualTo("叶惠美");

        // 断言请求 URL 携带了正确的歌单 ID
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("id=3778678");
    }

    @Test
    void parsePlaylist_invalidUrl_throws() {
        assertThatThrownBy(() -> adapter.parsePlaylist("https://example.com/not-a-playlist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法从链接中识别");
    }

    @Test
    void parsePlaylist_plainNumericId_works() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"playlist\":{\"tracks\":[]}}")
                .setHeader("Content-Type", "application/json"));

        adapter.parsePlaylist("3778678");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("id=3778678");
    }

    @Test
    void search_returnsCandidates() throws Exception {
        // 模拟搜索响应(网易云搜索字段:artists/album/duration 毫秒)
        String body = """
                {"result":{"songs":[
                  {"id":2001,"name":"晴天","artists":[{"name":"周杰伦"}],"album":{"name":"叶惠美"},"duration":269000,"pop":100},
                  {"id":2002,"name":"晴天","artists":[{"name":"翻唱者"}],"album":{"name":"cover"},"duration":240000,"pop":50}
                ]},"code":200}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        List<TrackCandidate> songs = adapter.search("晴天", "周杰伦");

        assertThat(songs).hasSize(2);
        assertThat(songs.get(0).title()).isEqualTo("晴天");
        assertThat(songs.get(0).artist()).isEqualTo("周杰伦");
        assertThat(songs.get(0).durationSec()).isEqualTo(269);
        assertThat(songs.get(0).popularity()).isEqualTo(100);
        assertThat(songs.get(0).playUrl()).contains("2001");
    }

    @Test
    void search_httpError_returnsEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(500));
        List<TrackCandidate> songs = adapter.search("晴天", "周杰伦");
        assertThat(songs).isEmpty();
    }

    @Test
    void detail_returnsTrack() throws Exception {
        String body = """
                {"songs":[
                  {"id":186016,"name":"晴天","artists":[{"name":"周杰伦"}],"album":{"name":"叶惠美"},"duration":269000}
                ]}
                """;
        server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));

        TrackCandidate detail = adapter.detail("186016");

        assertThat(detail).isNotNull();
        assertThat(detail.title()).isEqualTo("晴天");
        assertThat(detail.durationSec()).isEqualTo(269);
    }

    @Test
    void detail_notFound_returnsNull() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"songs\":[]}").setHeader("Content-Type", "application/json"));
        assertThat(adapter.detail("999999")).isNull();
    }

    @Test
    void extractPlaylistId_variants() {
        assertThat(NetEaseAdapter.extractPlaylistId("3778678")).isEqualTo("3778678");
        assertThat(NetEaseAdapter.extractPlaylistId("https://music.163.com/#/playlist?id=3778678")).isEqualTo("3778678");
        assertThat(NetEaseAdapter.extractPlaylistId("https://y.music.163.com/m/playlist?id=3778678&userid=1")).isEqualTo("3778678");
        assertThat(NetEaseAdapter.extractPlaylistId("   ")).isNull();
        assertThat(NetEaseAdapter.extractPlaylistId("https://example.com/x")).isNull();
    }
}
