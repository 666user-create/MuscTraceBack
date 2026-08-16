package org.example.musictraceback.web;

import org.example.musictraceback.web.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 健康检查接口:前端首页据此展示"后端已就绪"状态。
 * 返回结构见 HealthResponse,前端 app.js 用 fetch 调用。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                "MusicTraceBack",
                "H2 (file mode)",
                LocalDateTime.now().toString()
        );
    }
}
