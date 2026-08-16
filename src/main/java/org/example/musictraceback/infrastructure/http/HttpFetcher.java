package org.example.musictraceback.infrastructure.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 HTTP 客户端封装(JDK 内置 HttpClient)。
 *
 * 内置两项平台友好策略(设计文档 NFR-1/风险应对):
 * 1. 按主机限流:同一平台的连续请求之间强制最小间隔,降低触发风控概率;
 * 2. 风控退避:响应含 code=406(操作频繁)时自动等待后重试一次,
 *    仍失败则抛 RateLimitedException 由上层降级。
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final HttpClient client;
    /** 每主机最小请求间隔(毫秒) */
    private final long minIntervalMillis;
    /** 风控退避等待(毫秒) */
    private final long backoffMillis;
    /** 主机 → 上次请求时间 */
    private final ConcurrentHashMap<String, Long> lastRequestAt = new ConcurrentHashMap<>();

    public HttpFetcher(@Value("${music.http.min-interval-ms:800}") long minIntervalMillis,
                       @Value("${music.http.backoff-ms:5000}") long backoffMillis) {
        this.minIntervalMillis = minIntervalMillis;
        this.backoffMillis = backoffMillis;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET 请求,返回响应体字符串。失败时抛 IOException。
     *
     * @throws RateLimitedException 平台风控(已重试仍 406)
     */
    public String get(String url, String userAgent) throws IOException, InterruptedException {
        return get(url, userAgent, 1);
    }

    private String get(String url, String userAgent, int attempt) throws IOException, InterruptedException {
        throttle(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .header("Referer", refererOf(url))
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        String body = response.body();

        // 风控检测:网易云返回 {"code":406,"msg":"操作频繁，请稍候再试"}
        if (body.contains("\"code\":406") || body.contains("操作频繁")) {
            if (attempt < 2) {
                log.warn("平台风控触发(406),退避 {}ms 后重试: {}", backoffMillis, url);
                Thread.sleep(backoffMillis);
                return get(url, userAgent, attempt + 1);
            }
            throw new RateLimitedException("平台风控:请求过于频繁,请稍后重试");
        }
        return body;
    }

    /** 同一主机请求前强制最小间隔 */
    private void throttle(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            return;
        }
        long now = System.nanoTime();
        Long last = lastRequestAt.put(host, now);
        if (last != null) {
            long elapsedNanos = now - last;
            long intervalNanos = TimeUnit.MILLISECONDS.toNanos(minIntervalMillis);
            if (elapsedNanos < intervalNanos) {
                long waitMillis = TimeUnit.NANOSECONDS.toMillis(intervalNanos - elapsedNanos) + 1;
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** 取 URL 的协议+主机作为 Referer(避免 403) */
    private String refererOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return "";
        }
    }

    /** 平台风控异常(非受检,便于跨层传播;上层捕获后做降级处理) */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }
}
