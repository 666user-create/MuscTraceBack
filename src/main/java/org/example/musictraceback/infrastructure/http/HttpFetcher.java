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
 * 平台友好策略(设计文档 NFR-1/风险应对):
 * 1. 按主机限流:同一平台连续请求之间强制最小间隔,降低风控概率;
 * 2. 平台冷却:响应含 code=406(操作频繁)时,该主机进入冷却期(cooldownMillis),
 *    期间所有请求直接拒绝(不浪费请求数),冷却结束自动恢复;
 * 3. 上层(InjectionProcessor)感知冷却后,自动暂停该平台的条目处理,冷却期结束继续。
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final HttpClient client;
    /** 每主机最小请求间隔(毫秒) */
    private final long minIntervalMillis;
    /** 风控冷却期(毫秒) */
    private final long cooldownMillis;
    /** 主机 → 上次请求时间(nanoTime) */
    private final ConcurrentHashMap<String, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** 主机 → 冷却截止时间(epoch millis) */
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    public HttpFetcher(@Value("${music.http.min-interval-ms:800}") long minIntervalMillis,
                       @Value("${music.http.cooldown-ms:600000}") long cooldownMillis) {
        this.minIntervalMillis = minIntervalMillis;
        this.cooldownMillis = cooldownMillis;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET 请求,返回响应体字符串。
     *
     * @throws RateLimitedException 平台风控或冷却期内
     */
    public String get(String url, String userAgent) throws IOException, InterruptedException {
        String host = hostOf(url);

        // 冷却检查:期内直接拒绝,不发请求
        Long until = cooldownUntil.get(host);
        if (until != null && System.currentTimeMillis() < until) {
            throw new RateLimitedException("平台冷却中,剩余 " + ((until - System.currentTimeMillis()) / 1000) + "s");
        }
        if (until != null) {
            cooldownUntil.remove(host); // 冷却已结束,恢复
        }

        throttle(host);

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
            long cooldownEnd = System.currentTimeMillis() + cooldownMillis;
            cooldownUntil.put(host, cooldownEnd);
            log.warn("平台风控触发(406),主机 {} 进入冷却 {}s", host, cooldownMillis / 1000);
            throw new RateLimitedException("平台风控:进入冷却期,稍后自动恢复");
        }
        return body;
    }

    /** 指定主机是否处于冷却期 */
    public boolean isCoolingDown(String host) {
        Long until = cooldownUntil.get(host);
        return until != null && System.currentTimeMillis() < until;
    }

    /** 冷却剩余秒数(未冷却返回 0) */
    public long cooldownRemainingSeconds(String host) {
        Long until = cooldownUntil.get(host);
        if (until == null) {
            return 0;
        }
        long remaining = (until - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    /** 同一主机请求前强制最小间隔 */
    private void throttle(String host) {
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

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** 取 URL 的协议+主机作为 Referer(避免 403) */
    private static String refererOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return "";
        }
    }

    /** 平台风控/冷却异常(非受检,便于跨层传播) */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }
}
