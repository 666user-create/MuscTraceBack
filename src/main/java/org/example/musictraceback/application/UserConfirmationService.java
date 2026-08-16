package org.example.musictraceback.application;

import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.UserConfirmation;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.UserConfirmationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户确认服务(设计文档 9 节第 4 层兜底)。
 *
 * 用户对中/低置信度条目确认"原版"或"选择正确版本"后:
 * 1. 记录 UserConfirmation(数据沉淀);
 * 2. 回写 PlaylistEntry 的置信度为 HIGH、版本类型为用户判定;
 * 3. 后续体检同歌单时命中身份缓存,直接显示用户选择(越用越准)。
 */
@Service
public class UserConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(UserConfirmationService.class);

    private final UserConfirmationRepository confirmationRepository;
    private final PlaylistEntryRepository playlistEntryRepository;

    public UserConfirmationService(UserConfirmationRepository confirmationRepository,
                                   PlaylistEntryRepository playlistEntryRepository) {
        this.confirmationRepository = confirmationRepository;
        this.playlistEntryRepository = playlistEntryRepository;
    }

    /**
     * 用户确认歌单条目。
     *
     * @param playlistEntryId      歌单条目 ID
     * @param canonicalTrackId     用户确认的真实身份(可为 null,表示无法确认身份)
     * @param chosenPlatformTrackId 用户选择的平台版本 ID(可为 null)
     * @param chosenVersionType    用户判定的版本类型
     */
    @Transactional
    public UserConfirmation confirm(Long playlistEntryId,
                                    Long canonicalTrackId,
                                    String chosenPlatformTrackId,
                                    VersionType chosenVersionType) {
        PlaylistEntry entry = playlistEntryRepository.findById(playlistEntryId)
                .orElseThrow(() -> new IllegalArgumentException("歌单条目不存在: " + playlistEntryId));

        // 关联身份(若用户确认了身份)
        CanonicalTrack track = null;
        if (canonicalTrackId != null) {
            // 通过条目关联的身份校验;用户确认的身份可能是既有身份
            track = entry.getCanonicalTrack();
            if (track == null || !track.getId().equals(canonicalTrackId)) {
                throw new IllegalArgumentException("身份 ID 与歌单条目不匹配");
            }
        }

        UserConfirmation confirmation = UserConfirmation.builder()
                .playlistEntry(entry)
                .canonicalTrack(track)
                .chosenPlatformTrackId(chosenPlatformTrackId)
                .chosenVersionType(chosenVersionType)
                .build();
        UserConfirmation saved = confirmationRepository.save(confirmation);

        // 回写条目:确认后置信度提升为 HIGH
        entry.setConfidence(Confidence.HIGH);
        entry.setJudgedVersionType(chosenVersionType);
        playlistEntryRepository.save(entry);

        log.info("用户确认条目 {}: versionType={}, platformTrackId={}",
                playlistEntryId, chosenVersionType, chosenPlatformTrackId);
        return saved;
    }

    /** 查某条目的确认记录 */
    public List<UserConfirmation> findByPlaylistEntryId(Long playlistEntryId) {
        return confirmationRepository.findByPlaylistEntryId(playlistEntryId);
    }
}
