package org.example.musictraceback.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步注入配置。
 *
 * 关键设计:注入任务执行器是**单线程**的。
 * 平台风控的核心触发条件是"请求频率",单线程串行处理(配合 HttpFetcher
 * 的 min-interval 限流)天然控制频率,大幅降低风控概率;多个歌单同时注入
 * 也只排队串行,不会互相抢请求额度。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "injectionExecutor")
    public Executor injectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("injection-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("注入任务被拒绝(队列已满),请稍后重试: {}", r));
        executor.initialize();
        return executor;
    }
}
