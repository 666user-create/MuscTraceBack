package org.example.musictraceback.infrastructure.http;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpFetcher 冷却机制测试:406 → 冷却 → 期内拒绝 → 冷却结束恢复。
 */
class HttpFetcherCooldownTests {

    private MockWebServer server;
    private HttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // 冷却 300ms(测试快速验证)
        fetcher = new HttpFetcher(0, 300);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void rateLimit_entersCooldown_thenRecovers() throws Exception {
        String host = server.getHostName();

        // 第一次:平台返回 406 风控
        server.enqueue(new MockResponse().setBody("{\"code\":406,\"msg\":\"操作频繁\"}")
                .setHeader("Content-Type", "application/json"));
        assertThatThrownBy(() -> fetcher.get(server.url("/x").toString(), "ua"))
                .isInstanceOf(HttpFetcher.RateLimitedException.class);

        // 触发后进入冷却
        assertThat(fetcher.isCoolingDown(host)).isTrue();

        // 冷却期内:请求被直接拒绝(MockWebServer 不会再收到请求)
        assertThatThrownBy(() -> fetcher.get(server.url("/y").toString(), "ua"))
                .isInstanceOf(HttpFetcher.RateLimitedException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);

        // 等待冷却结束
        Thread.sleep(400);
        assertThat(fetcher.isCoolingDown(host)).isFalse();

        // 恢复:正常响应
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"ok\":true}")
                .setHeader("Content-Type", "application/json"));
        String body = fetcher.get(server.url("/z").toString(), "ua");
        assertThat(body).contains("\"code\":200");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void noRateLimit_noCooldown() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200}")
                .setHeader("Content-Type", "application/json"));
        fetcher.get(server.url("/ok").toString(), "ua");

        assertThat(fetcher.isCoolingDown(server.getHostName())).isFalse();
        assertThat(fetcher.cooldownRemainingSeconds(server.getHostName())).isZero();
    }

    @Test
    void httpError_notCooldown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/err").toString(), "ua"))
                .isInstanceOf(IOException.class);
        assertThat(fetcher.isCoolingDown(server.getHostName())).isFalse();
    }
}
