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
 * HttpFetcher 断路器测试:连续失败达到阈值 → 断路期内快速失败(不发请求)→ 探测恢复 → 失败计数清零。
 */
class HttpFetcherCircuitBreakerTests {

    private MockWebServer server;
    /** 阈值 3 次,断路 300ms(测试快速验证) */
    private HttpFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        fetcher = new HttpFetcher(0, 60_000, 3, 300);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void consecutiveFailures_openCircuit_fastFailThenRecover() throws Exception {
        // 连续 3 次 500:每次都真正发出请求
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500));
            assertThatThrownBy(() -> fetcher.get(server.url("/x").toString(), "ua"))
                    .isInstanceOf(IOException.class);
        }
        assertThat(server.getRequestCount()).isEqualTo(3);

        // 断路:第 4 次不发请求,快速失败且提示断路
        assertThatThrownBy(() -> fetcher.get(server.url("/y").toString(), "ua"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("断路");
        assertThat(server.getRequestCount()).isEqualTo(3);

        // 断路结束:探测请求真正发出且成功后恢复
        Thread.sleep(400);
        server.enqueue(new MockResponse().setBody("{\"ok\":true}")
                .setHeader("Content-Type", "application/json"));
        assertThat(fetcher.get(server.url("/z").toString(), "ua")).contains("ok");
        assertThat(server.getRequestCount()).isEqualTo(4);

        // 恢复后再次失败:重新从 1 计数(不立即断路,请求照常发出)
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/w").toString(), "ua"))
                .isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).isEqualTo(5);
    }

    @Test
    void successResetsFailureCount() throws Exception {
        // 2 次失败(未达阈值)+ 1 次成功 → 计数清零
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/a").toString(), "ua"))
                .isInstanceOf(IOException.class);
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/b").toString(), "ua"))
                .isInstanceOf(IOException.class);
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        fetcher.get(server.url("/c").toString(), "ua");

        // 再失败 2 次仍不应断路(否则说明成功未清零)
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/d").toString(), "ua"))
                .isInstanceOf(IOException.class);
        server.enqueue(new MockResponse().setResponseCode(500));
        assertThatThrownBy(() -> fetcher.get(server.url("/e").toString(), "ua"))
                .isInstanceOf(IOException.class);

        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        assertThat(fetcher.get(server.url("/f").toString(), "ua")).contains("ok");
        assertThat(server.getRequestCount()).isEqualTo(6);
    }
}
