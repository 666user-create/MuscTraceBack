package org.example.musictraceback.infrastructure.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * 3. 上层(InjectionProcessor)感知冷却后,自动暂停该平台的条目处理,冷却期结束继续;
 * 4. 断路器:同一主机连续失败(网络异常/5xx)达到阈值后进入短暂"不可用"期,
 *    期间请求立即失败(不发网络调用),避免单个平台接口挂掉拖垮整体处理速度。
 *    断路冷却以 IOException 形式抛出 → 适配器降级跳过该平台,不暂停整个歌单。
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private final HttpClient client;
    /** 每主机最小请求间隔(毫秒) */
    private final long minIntervalMillis;
    /** 风控冷却期(毫秒) */
    private final long cooldownMillis;
    /** 断路:连续失败阈值 */
    private final int failureThreshold;
    /** 断路:主机不可用期(毫秒) */
    private final long failureCooldownMillis;
    /** 主机 → 上次请求时间(nanoTime) */
    private final ConcurrentHashMap<String, Long> lastRequestAt = new ConcurrentHashMap<>();
    /** 主机 → 风控冷却截止时间(epoch millis) */
    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    /** 主机 → 连续失败次数 */
    private final ConcurrentHashMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    /** 主机 → 断路截止时间(epoch millis) */
    private final ConcurrentHashMap<String, Long> failureCooldownUntil = new ConcurrentHashMap<>();

    public HttpFetcher(long minIntervalMillis, long cooldownMillis) {
        this(minIntervalMillis, cooldownMillis, 3, 120_000);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpFetcher(@Value("${music.http.min-interval-ms:800}") long minIntervalMillis,
                       @Value("${music.http.cooldown-ms:600000}") long cooldownMillis,
                       @Value("${music.http.failure-threshold:3}") int failureThreshold,
                       @Value("${music.http.failure-cooldown-ms:120000}") long failureCooldownMillis) {
        this.minIntervalMillis = minIntervalMillis;
        this.cooldownMillis = cooldownMillis;
        this.failureThreshold = failureThreshold;
        this.failureCooldownMillis = failureCooldownMillis;
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
        return send(url, userAgent, null);
    }

    /**
     * POST JSON 请求,返回响应体字符串。
     *
     * @throws RateLimitedException 平台风控或冷却期内
     */
    public String post(String url, String jsonBody, String userAgent) throws IOException, InterruptedException {
        return send(url, userAgent, jsonBody);
    }

    private String send(String url, String userAgent, String jsonBody) throws IOException, InterruptedException {
        String host = hostOf(url);

        // 风控冷却检查:期内直接拒绝,不发请求
        Long until = cooldownUntil.get(host);
        if (until != null && System.currentTimeMillis() < until) {
            throw new RateLimitedException("平台冷却中,剩余 " + ((until - System.currentTimeMillis()) / 1000) + "s");
        }
        if (until != null) {
            cooldownUntil.remove(host); // 冷却已结束,恢复
        }

        // 断路检查:主机连续失败冷却中,立即失败(不发请求,快速降级)
        Long failUntil = failureCooldownUntil.get(host);
        if (failUntil != null && System.currentTimeMillis() < failUntil) {
            throw new IOException("主机 " + host + " 连续失败,断路中,剩余 "
                    + ((failUntil - System.currentTimeMillis()) / 1000) + "s");
        }
        if (failUntil != null) {
            failureCooldownUntil.remove(host);
            consecutiveFailures.remove(host);
        }

        throttle(host);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .header("Referer", refererOf(url))
                .header("Accept", "application/json, text/plain, */*");
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            recordFailure(host);
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            recordFailure(host);
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        String body = response.body();
        recordSuccess(host);

        // 风控检测:网易云返回 {"code":406,"msg":"操作频繁，请稍候再试"}
        if (body.contains("\"code\":406") || body.contains("操作频繁")) {
            long cooldownEnd = System.currentTimeMillis() + cooldownMillis;
            cooldownUntil.put(host, cooldownEnd);
            log.warn("平台风控触发(406),主机 {} 进入冷却 {}s", host, cooldownMillis / 1000);
            throw new RateLimitedException("平台风控:进入冷却期,稍后自动恢复");
        }
        return body;
    }

    /** 记录一次失败;连续失败达到阈值则断路 */
    private void recordFailure(String host) {
        int failures = consecutiveFailures.merge(host, 1, Integer::sum);
        if (failures >= failureThreshold) {
            failureCooldownUntil.put(host, System.currentTimeMillis() + failureCooldownMillis);
            log.warn("主机 {} 连续失败 {} 次,进入断路 {}s(期间快速跳过该平台)",
                    host, failures, failureCooldownMillis / 1000);
        }
    }

    /** 记录一次成功,清零失败计数 */
    private void recordSuccess(String host) {
        consecutiveFailures.remove(host);
        failureCooldownUntil.remove(host);
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
