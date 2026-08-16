package org.example.musictraceback.infrastructure.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内简单搜索缓存(设计文档 NFR-3/风险应对)。
 *
 * 身份解析与版本发现对同一 (platform, keyword) 会重复发起平台搜索,
 * 用此缓存复用结果,显著减少请求量(降低风控概率)。
 *
 * 注意:这是 M3 的轻量方案(ConcurrentHashMap + 过期时间),
 * 后续可平滑替换为 Caffeine 等专门缓存库。
 */
@Component
public class SearchCache {

    /** key → (值, 过期时间戳) */
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SearchCache(@Value("${music.cache.search-ttl-ms:600000}") long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 缓存条目(值 + 过期时间) */
    private record Entry(Object value, long expireAt) {
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String platform, String keyword) {
        Entry e = map.get(key(platform, keyword));
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expireAt()) {
            map.remove(key(platform, keyword));
            return null;
        }
        return (T) e.value();
    }

    public void put(String platform, String keyword, Object value) {
        map.put(key(platform, keyword), new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    public void clear() {
        map.clear();
    }

    /** 当前缓存条目数(测试/监控用) */
    public int size() {
        return map.size();
    }

    private static String key(String platform, String keyword) {
        return platform + "::" + keyword;
    }
}
