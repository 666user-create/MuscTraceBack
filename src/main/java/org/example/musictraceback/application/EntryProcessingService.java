package org.example.musictraceback.application;

import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.EntryStatus;
import org.example.musictraceback.infrastructure.http.HttpFetcher;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 条目处理服务(单条独立事务)。
 *
 * 每条条目一个事务:风控异常只回滚当前条目(保持未处理),
 * 不影响本快照其他已提交的进度。
 */
@Service
public class EntryProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EntryProcessingService.class);

    private final PlaylistEntryRepository playlistEntryRepository;
    private final IdentityResolutionService identityResolutionService;
    private final VersionDiscoveryService versionDiscoveryService;

    public EntryProcessingService(PlaylistEntryRepository playlistEntryRepository,
                                  IdentityResolutionService identityResolutionService,
                                  VersionDiscoveryService versionDiscoveryService) {
        this.playlistEntryRepository = playlistEntryRepository;
        this.identityResolutionService = identityResolutionService;
        this.versionDiscoveryService = versionDiscoveryService;
    }

    /**
     * 处理单条条目(独立事务)。
     * 本地建档 → 来源版本入库 → 其他平台发现 → 状态判定 → 标记完成。
     *
     * @throws HttpFetcher.RateLimitedException 平台风控/冷却:本条回滚保持未处理
     */
    @Transactional
    public void processOne(Long entryId) {
        PlaylistEntry entry = playlistEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("条目不存在: " + entryId));
        try {
            // 1. 本地建档(零请求)
            IdentityResolutionService.Resolution resolution =
                    identityResolutionService.resolveLocal(entry.getRawTitle(), entry.getRawArtist());
            CanonicalTrack track = resolution.track();

            // 2. 来源平台版本零请求入库 + 其他平台发现
            if (track != null) {
                entry.setCanonicalTrack(track);
                entry.setConfidence(resolution.confidence());
                entry.setJudgedVersionType(resolution.judgedVersionType());

                String sourcePlatformId = entry.getSnapshot() != null ? entry.getSnapshot().getPlatform() : null;
                if (sourcePlatformId != null) {
                    versionDiscoveryService.addSourceVersion(track, sourcePlatformId,
                            entry.getPlatformTrackId(), entry.getRawTitle(), entry.getRawArtist(), "");
                    // 其他平台搜索(可能抛 RateLimitedException)
                    versionDiscoveryService.discoverOtherPlatforms(
                            track, entry.getRawTitle(), entry.getRawArtist(), sourcePlatformId);
                }
            } else {
                entry.setConfidence(resolution.confidence());
            }

            // 3. 状态判定(FR-5)
            markEntryStatus(entry);
            entry.setProcessed(true);
            playlistEntryRepository.save(entry);
        } catch (HttpFetcher.RateLimitedException e) {
            // 风控:本事务回滚 → 条目保持未处理,由编排层重试
            log.info("条目 {} ({}) 因平台风控暂停: {}", entry.getId(), entry.getRawTitle(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("条目 {} ({}) 处理失败,标记已处理(用户可人工确认): {}",
                    entry.getId(), entry.getRawTitle(), e.getMessage());
            entry.setConfidence(Confidence.LOW);
            entry.setProcessed(true);
            playlistEntryRepository.save(entry);
        }
    }

    /** 曲目状态判定(同 M3 规则) */
    private void markEntryStatus(PlaylistEntry entry) {
        if (entry.getCanonicalTrack() == null) {
            entry.setEntryStatus(EntryStatus.DELISTED);
            return;
        }
        switch (entry.getJudgedVersionType()) {
            case COVER, REMIX, INSTRUMENTAL, LIVE, OTHER -> {
                if (entry.getConfidence() == Confidence.HIGH || entry.getConfidence() == Confidence.MEDIUM) {
                    entry.setEntryStatus(EntryStatus.REPLACED);
                } else {
                    entry.setEntryStatus(EntryStatus.DELISTED);
                }
            }
            default -> entry.setEntryStatus(EntryStatus.NORMAL);
        }
    }
}
