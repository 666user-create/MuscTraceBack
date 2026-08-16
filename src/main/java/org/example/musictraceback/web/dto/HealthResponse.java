package org.example.musictraceback.web.dto;

/**
 * 健康检查响应体(record 自动生成构造器/访问器,Jackson 直接序列化)
 */
public record HealthResponse(
        String status,
        String app,
        String database,
        String time
) {
}
