package org.example.musictraceback.application;

import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.infrastructure.http.HttpFetcher;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 异步注入处理器(风控优化的核心编排层,无事务)。
 *
 * 单线程串行处理(@Async("injectionExecutor")),配合 HttpFetcher 限流/冷却:
 * 1. 循环取未处理条目 → EntryProcessingService.processOne(单条独立事务);
 * 2. 遇风控(RateLimitedException)→ 该条保持未处理,暂停 10s 后继续;
 * 3. 全部处理完 → 快照置 COMPLETED;
 * 4. 应用重启后 StartupResumeRunner 继续 PROCESSING 快照(断点续跑);
 * 5. 重试超限 → 保持 PROCESSING,可由用户触发 resume。
 */
@Service
public class InjectionProcessor {

    private static final Logger log = LoggerFactory.getLogger(InjectionProcessor.class);
    /** 风控暂停后重试间隔(毫秒) */
    private static final long RETRY_INTERVAL_MS = 10_000;
    /** 单次处理循环最大连续风控重试次数(约 10 分钟) */
    private static final int MAX_RETRIES = 60;

    private final SnapshotRepository snapshotRepository;
    private final PlaylistEntryRepository playlistEntryRepository;
    private final EntryProcessingService entryProcessingService;

    public InjectionProcessor(SnapshotRepository snapshotRepository,
                              PlaylistEntryRepository playlistEntryRepository,
                              EntryProcessingService entryProcessingService) {
        this.snapshotRepository = snapshotRepository;
        this.playlistEntryRepository = playlistEntryRepository;
        this.entryProcessingService = entryProcessingService;
    }

    /**
     * 后台处理整个快照(异步,单线程执行器)。
     */
    @Async("injectionExecutor")
    public void processSnapshot(Long snapshotId) {
        log.info("开始后台处理歌单快照 {}", snapshotId);
        try {
            int retries = 0;
            while (true) {
                // 取一条未处理条目(每次处理一条,进度可即时反映到前端)
                PlaylistEntry next = nextPending(snapshotId);
                if (next == null) {
                    markCompleted(snapshotId);
                    log.info("歌单快照 {} 处理完成", snapshotId);
                    return;
                }
                try {
                    entryProcessingService.processOne(next.getId());
                    retries = 0; // 成功处理,重置重试计数
                } catch (HttpFetcher.RateLimitedException e) {
                    retries++;
                    if (retries > MAX_RETRIES) {
                        log.warn("歌单快照 {} 连续风控 {} 次,暂停(保持 PROCESSING 可续跑)",
                                snapshotId, MAX_RETRIES);
                        return;
                    }
                    long remaining = countPending(snapshotId);
                    log.info("歌单快照 {} 风控等待:剩 {} 首,{}s 后继续", snapshotId, remaining, RETRY_INTERVAL_MS / 1000);
                    Thread.sleep(RETRY_INTERVAL_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("歌单快照 {} 处理被中断,保持 PROCESSING", snapshotId);
        } catch (Exception e) {
            log.error("歌单快照 {} 处理异常,保持 PROCESSING 等待续跑: {}", snapshotId, e.getMessage(), e);
        }
    }

    private PlaylistEntry nextPending(Long snapshotId) {
        List<PlaylistEntry> pending = playlistEntryRepository.findBySnapshotIdAndProcessedFalse(snapshotId);
        return pending.isEmpty() ? null : pending.get(0);
    }

    private long countPending(Long snapshotId) {
        return playlistEntryRepository.findBySnapshotIdAndProcessedFalse(snapshotId).size();
    }

    private void markCompleted(Long snapshotId) {
        Snapshot snapshot = snapshotRepository.findById(snapshotId).orElse(null);
        if (snapshot != null && !"COMPLETED".equals(snapshot.getStatus())) {
            snapshot.setStatus("COMPLETED");
            snapshotRepository.save(snapshot);
        }
    }
}
