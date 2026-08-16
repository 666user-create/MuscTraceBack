package org.example.musictraceback.application;

import org.example.musictraceback.adapter.PlatformAdapter;
import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 歌单注入编排(设计文档 FR-1 → FR-4 主流程)。
 *
 * 用户粘贴链接 → 识别平台 → 解析曲目 → 逐条身份解析 → 版本发现。
 * 注入期间使用 PROCESSING 状态,完成后置为 COMPLETED;失败置 FAILED。
 */
@Service
public class PlaylistInjectionService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistInjectionService.class);

    private final SnapshotRepository snapshotRepository;
    private final IdentityResolutionService identityResolutionService;
    private final VersionDiscoveryService versionDiscoveryService;
    private final List<PlatformAdapter> adapters;

    public PlaylistInjectionService(SnapshotRepository snapshotRepository,
                                    IdentityResolutionService identityResolutionService,
                                    VersionDiscoveryService versionDiscoveryService,
                                    List<PlatformAdapter> adapters) {
        this.snapshotRepository = snapshotRepository;
        this.identityResolutionService = identityResolutionService;
        this.versionDiscoveryService = versionDiscoveryService;
        this.adapters = adapters;
    }

    /**
     * 注入歌单:解析 + 逐条身份解析 + 版本发现。
     *
     * @param shareUrlOrId 歌单分享链接或 ID
     * @return 保存的快照(含解析后的条目)
     */
    @Transactional
    public Snapshot inject(String shareUrlOrId) {
        // 1. 识别平台
        PlatformAdapter adapter = detectPlatform(shareUrlOrId);
        if (adapter == null) {
            throw new IllegalArgumentException("无法识别歌单平台,目前支持: 网易云 / QQ音乐 / 酷狗(粘贴分享链接)");
        }

        // 2. 创建快照(PROCESSING)
        Snapshot snapshot = Snapshot.builder()
                .platform(adapter.platformId())
                .sourceUrl(shareUrlOrId)
                .status("PROCESSING")
                .build();
        snapshot = snapshotRepository.save(snapshot);

        try {
            // 3. 解析曲目
            List<PlaylistEntryRaw> rawEntries = adapter.parsePlaylist(shareUrlOrId);
            if (rawEntries.isEmpty()) {
                snapshot.setStatus("FAILED");
                return snapshotRepository.save(snapshot);
            }
            snapshot.setTitle("歌单(" + rawEntries.size() + "首)");

            // 4. 逐条:身份解析 → 版本发现 → 状态判定
            for (PlaylistEntryRaw raw : rawEntries) {
                PlaylistEntry entry = PlaylistEntry.builder()
                        .snapshot(snapshot)
                        .platformTrackId(raw.platformTrackId())
                        .rawTitle(raw.title())
                        .rawArtist(raw.artist())
                        .build();
                snapshot.addEntry(entry);

                IdentityResolutionService.Resolution resolution =
                        identityResolutionService.resolve(raw.title(), raw.artist(), adapter.platformId());
                if (resolution.track() != null) {
                    entry.setCanonicalTrack(resolution.track());
                    entry.setConfidence(resolution.confidence());
                    entry.setJudgedVersionType(resolution.judgedVersionType());
                    // 版本发现(仅对身份已解析的条目)
                    try {
                        versionDiscoveryService.discover(resolution.track(), raw.title(), raw.artist());
                    } catch (Exception e) {
                        log.warn("版本发现失败 {}: {}", raw.title(), e.getMessage());
                    }
                } else {
                    entry.setConfidence(resolution.confidence());
                    entry.setJudgedVersionType(resolution.judgedVersionType());
                }

                // 状态判定(设计文档 FR-5):
                //  - 身份解析成功且有原版候选 → 源平台版本可用,标记 NORMAL
                //  - 身份解析失败(平台搜索无结果) → 源平台可能下架,标记 DELISTED(待用户确认)
                //  - 身份解析成功但判定为翻唱/其他版本 → 标记 REPLACED(被替换)
                markEntryStatus(entry);
            }

            snapshot.setStatus("COMPLETED");
        } catch (Exception e) {
            log.error("歌单注入失败: {}", e.getMessage(), e);
            snapshot.setStatus("FAILED");
        }
        return snapshotRepository.save(snapshot);
    }

    /** 按链接特征识别平台 */
    private PlatformAdapter detectPlatform(String shareUrlOrId) {
        String s = shareUrlOrId == null ? "" : shareUrlOrId.trim();
        if (s.matches("\\d+")) {
            // 纯数字 ID 无法判断平台,默认按"最近使用/首平台"处理 → 网易云
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

    /** 查快照(含条目) */
    @Transactional(readOnly = true)
    public Optional<Snapshot> findById(Long id) {
        return snapshotRepository.findById(id);
    }

    /**
     * 曲目状态判定(FR-5 下架/替换追查的基础版)。
     *
     * 判定规则(启发式,后续可结合平台"无版权"标记增强):
     *  - 身份解析失败(无候选/低置信) → 源平台可能已下架 → DELISTED
     *  - 身份解析成功但判定为翻唱/Remix/伴奏 → 歌单里的版本已被替换 → REPLACED
     *  - 其余 → NORMAL
     */
    private void markEntryStatus(PlaylistEntry entry) {
        if (entry.getCanonicalTrack() == null) {
            entry.setEntryStatus(org.example.musictraceback.domain.enums.EntryStatus.DELISTED);
            return;
        }
        switch (entry.getJudgedVersionType()) {
            case COVER, REMIX, INSTRUMENTAL, LIVE, OTHER -> {
                // 歌单里的原条目判定为非常见原版形态 → 可能被替换
                if (entry.getConfidence() == org.example.musictraceback.domain.enums.Confidence.HIGH
                        || entry.getConfidence() == org.example.musictraceback.domain.enums.Confidence.MEDIUM) {
                    entry.setEntryStatus(org.example.musictraceback.domain.enums.EntryStatus.REPLACED);
                } else {
                    entry.setEntryStatus(org.example.musictraceback.domain.enums.EntryStatus.DELISTED);
                }
            }
            default -> entry.setEntryStatus(org.example.musictraceback.domain.enums.EntryStatus.NORMAL);
        }
    }

    /**
     * 查快照详情(事务内初始化全部懒加载集合,供 Controller 组装 DTO 使用)。
     */
    @Transactional(readOnly = true)
    public Optional<Snapshot> findDetail(Long id) {
        return snapshotRepository.findById(id)
                .map(s -> {
                    // 触发懒加载:条目 + 每个条目的身份 + 身份的版本
                    int entryCount = s.getPlaylistEntries().size();
                    for (PlaylistEntry e : s.getPlaylistEntries()) {
                        if (e.getCanonicalTrack() != null) {
                            e.getCanonicalTrack().getPlatformVersions().size();
                        }
                    }
                    return s;
                });
    }
}
