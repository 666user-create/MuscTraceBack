package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 歌单注入编排(风控优化版)。
 *
 * 注入分两阶段:
 * 1. 同步(毫秒级):识别平台 → 解析歌单(1 次请求)→ 落库快照+条目 → 立即返回;
 * 2. 异步(InjectionProcessor):逐条身份解析(零请求本地建档)+ 跨平台版本发现,
 *    单线程慢速处理,前端轮询进度。
 *
 * 这样 200 首歌的注入不再同步等待 5-10 分钟,也不再一次性打满平台风控额度。
 */
@Service
public class PlaylistInjectionService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistInjectionService.class);

    private final SnapshotRepository snapshotRepository;
    private final PlaylistEntryRepository playlistEntryRepository;
    private final InjectionProcessor injectionProcessor;
    private final List<PlatformAdapter> adapters;

    public PlaylistInjectionService(SnapshotRepository snapshotRepository,
                                    PlaylistEntryRepository playlistEntryRepository,
                                    InjectionProcessor injectionProcessor,
                                    List<PlatformAdapter> adapters) {
        this.snapshotRepository = snapshotRepository;
        this.playlistEntryRepository = playlistEntryRepository;
        this.injectionProcessor = injectionProcessor;
        this.adapters = adapters;
    }

    /**
     * 注入歌单:同步解析+建档,立即返回快照(PROCESSING),后台异步处理。
     *
     * @param shareUrlOrId 歌单分享链接或 ID
     * @return 快照(此时 entryCount 已确定,processedCount=0)
     */
    @Transactional
    public Snapshot inject(String shareUrlOrId) {
        PlatformAdapter adapter = detectPlatform(shareUrlOrId);
        if (adapter == null) {
            throw new IllegalArgumentException("无法识别歌单平台,目前支持: 网易云 / QQ音乐 / 酷狗(粘贴分享链接)");
        }

        // 1. 解析歌单(单次平台请求)
        List<PlaylistEntryRaw> rawEntries;
        try {
            rawEntries = adapter.parsePlaylist(shareUrlOrId);
        } catch (Exception e) {
            log.error("歌单解析失败: {}", e.getMessage(), e);
            throw new IllegalStateException("歌单解析失败,请检查链接是否有效: " + e.getMessage());
        }
        if (rawEntries.isEmpty()) {
            throw new IllegalStateException("歌单为空或解析失败,请检查链接是否有效");
        }

        // 2. 快照 + 条目落库
        Snapshot snapshot = Snapshot.builder()
                .platform(adapter.platformId())
                .sourceUrl(shareUrlOrId)
                .title("歌单(" + rawEntries.size() + "首)")
                .status("PROCESSING")
                .build();
        snapshot = snapshotRepository.save(snapshot);
        for (PlaylistEntryRaw raw : rawEntries) {
            PlaylistEntry entry = PlaylistEntry.builder()
                    .snapshot(snapshot)
                    .platformTrackId(raw.platformTrackId())
                    .rawTitle(raw.title())
                    .rawArtist(raw.artist())
                    .build();
            snapshot.addEntry(entry);
        }
        snapshot = snapshotRepository.save(snapshot);
        log.info("歌单 {} 建档完成(共 {} 首),提交后台处理", adapter.platformId(), rawEntries.size());

        // 3. 异步处理(事务提交后执行,避免事务内发起后台任务读到未提交数据)
        final Long snapshotId = snapshot.getId();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            injectionProcessor.processSnapshot(snapshotId);
                        }
                    });
        } else {
            // 无事务环境(如测试直接调用):直接触发
            injectionProcessor.processSnapshot(snapshotId);
        }
        return snapshot;
    }

    /** 手动恢复处理(风控重试超限或重启后补跑) */
    @Transactional
    public void resume(Long id) {
        Snapshot snapshot = snapshotRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("歌单不存在: " + id));
        if ("COMPLETED".equals(snapshot.getStatus())
                && pendingCount(id) == 0) {
            return; // 已全部完成
        }
        if ("PROCESSING".equals(snapshot.getStatus())) {
            return; // 后台任务仍在跑
        }
        snapshot.setStatus("PROCESSING");
        snapshotRepository.save(snapshot);
        injectionProcessor.processSnapshot(id);
    }

    /** 查快照(含条目) */
    @Transactional(readOnly = true)
    public Optional<Snapshot> findById(Long id) {
        return snapshotRepository.findById(id);
    }

    /**
     * 查快照详情(事务内初始化全部懒加载集合,供 Controller 组装 DTO 使用)。
     */
    @Transactional(readOnly = true)
    public Optional<Snapshot> findDetail(Long id) {
        return snapshotRepository.findById(id)
                .map(s -> {
                    s.getPlaylistEntries().size();
                    for (PlaylistEntry e : s.getPlaylistEntries()) {
                        if (e.getCanonicalTrack() != null) {
                            e.getCanonicalTrack().getPlatformVersions().size();
                        }
                    }
                    return s;
                });
    }

    /** 进度:已处理条目数 */
    @Transactional(readOnly = true)
    public long processedCount(Long id) {
        return playlistEntryRepository.countBySnapshotIdAndProcessedTrue(id);
    }

    /** 进度:条目总数 */
    @Transactional(readOnly = true)
    public long entryCount(Long id) {
        return playlistEntryRepository.countBySnapshotId(id);
    }

    /** 进度:未处理条目数 */
    @Transactional(readOnly = true)
    public long pendingCount(Long id) {
        return playlistEntryRepository.findBySnapshotIdAndProcessedFalse(id).size();
    }

    /** 未处理条目 ID 列表(供重试诊断) */
    @Transactional(readOnly = true)
    public List<Long> pendingEntryIds(Long id) {
        return playlistEntryRepository.findBySnapshotIdAndProcessedFalse(id).stream()
                .map(PlaylistEntry::getId)
                .toList();
    }

    /** 按链接特征识别平台 */
    private PlatformAdapter detectPlatform(String shareUrlOrId) {
        String s = shareUrlOrId == null ? "" : shareUrlOrId.trim();
        if (s.matches("\\d+")) {
            return findAdapter("netease");
        }
        if (s.contains("music.163.com") || s.contains("163cn.tv") || s.contains("y.music.163.com")) {
            return findAdapter("netease");
        }
        if (s.contains("y.qq.com") || s.contains("c.y.qq.com") || s.contains("i.y.qq.com")) {
            return findAdapter("qq");
        }
        if (s.contains("kugou.com") || s.contains("kg.qq.com")) {
            return findAdapter("kugou");
        }
        return null;
    }

    private PlatformAdapter findAdapter(String platformId) {
        return adapters.stream()
                .filter(a -> a.platformId().equals(platformId))
                .findFirst()
                .orElse(null);
    }
}
