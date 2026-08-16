package org.example.musictraceback.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 歌曲真实身份:规范化歌名+歌手唯一确定(设计文档 CANONICAL_TRACK 表)
 */
@Entity
@Table(name = "canonical_track",
        uniqueConstraints = @UniqueConstraint(name = "uk_track_identity",
                columnNames = {"normalized_title", "normalized_artist"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CanonicalTrack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规范化后的歌名 */
    @Column(name = "normalized_title", nullable = false, length = 300)
    private String normalizedTitle;

    /** 规范化后的歌手 */
    @Column(name = "normalized_artist", nullable = false, length = 300)
    private String normalizedArtist;

    /** ISRC 国际标准录音编码(可空,加强标识用) */
    @Column(length = 20)
    private String isrc;

    /** MusicBrainz 录音 ID(可空,加强标识用) */
    @Column(length = 50)
    private String mbid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "canonicalTrack", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PlatformVersion> platformVersions = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 便捷方法:添加版本并维护双向关系 */
    public void addVersion(PlatformVersion version) {
        platformVersions.add(version);
        version.setCanonicalTrack(this);
    }
}
