package org.example.musictraceback.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import org.example.musictraceback.domain.enums.VersionType;

import java.time.LocalDateTime;

/**
 * 用户确认记录:用户对某歌单条目的判断(设计文档 USER_CONFIRMATION 表)
 */
@Entity
@Table(name = "user_confirmation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_entry_id", nullable = false)
    private PlaylistEntry playlistEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonical_track_id")
    private CanonicalTrack canonicalTrack;

    /** 用户选定的平台版本 ID */
    @Column(name = "chosen_platform_track_id", length = 100)
    private String chosenPlatformTrackId;

    /** 用户判定的版本类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "chosen_version_type", nullable = false, length = 20)
    private VersionType chosenVersionType;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private LocalDateTime confirmedAt;

    @PrePersist
    void onCreate() {
        if (confirmedAt == null) {
            confirmedAt = LocalDateTime.now();
        }
    }
}
