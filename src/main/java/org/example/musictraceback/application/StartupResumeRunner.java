package org.example.musictraceback.application;

import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 断点续跑:应用启动时,自动继续处理所有 PROCESSING 状态的快照。
 *
 * 场景:异步注入进行中用户关闭了应用,或风控重试超限导致任务暂停,
 * 重启后无需重新注入,未处理的条目自动继续。
 */
@Component
public class StartupResumeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupResumeRunner.class);

    private final SnapshotRepository snapshotRepository;
    private final InjectionProcessor injectionProcessor;

    public StartupResumeRunner(SnapshotRepository snapshotRepository,
                               InjectionProcessor injectionProcessor) {
        this.snapshotRepository = snapshotRepository;
        this.injectionProcessor = injectionProcessor;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Snapshot> pending = snapshotRepository.findByStatusOrderByCreatedAtDesc("PROCESSING");
        if (pending.isEmpty()) {
            return;
        }
        log.info("发现 {} 个未完成的歌单快照,自动续跑", pending.size());
        for (Snapshot snapshot : pending) {
            injectionProcessor.processSnapshot(snapshot.getId());
        }
    }
}
