package org.example.musictraceback.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;

import java.time.LocalDateTime;

/**
 * 同一真实身份在各平台的候选版本(设计文档 PLATFORM_VERSION 表)
 */
@Entity
@Table(name = "platform_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_platform_track",
                columnNames = {"platform", "platform_track_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PlatformVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "canonical_track_id", nullable = false)
    private CanonicalTrack canonicalTrack;

    /** 平台:netease / qq / kugou */
    @Column(nullable = false, length = 20)
    private String platform;

    /** 平台内歌曲 ID */
    @Column(name = "platform_track_id", nullable = false, length = 100)
    private String platformTrackId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 300)
    private String artist;

    @Column(length = 300)
    private String album;

    /** 时长(秒) */
    @Column(name = "duration_sec")
    private Integer durationSec;

    /** 版本类型:ORIGINAL/LIVE/COVER/REMIX/INSTRUMENTAL/OTHER */
    @Enumerated(EnumType.STRING)
    @Column(name = "version_type", nullable = false, length = 20)
    @Builder.Default
    private VersionType versionType = VersionType.OTHER;

    /** 平台原始热度值(播放量等) */
    @Column(name = "popularity_raw")
    private Long popularityRaw;

    /** 平台内热度百分位 0-100 */
    @Column(name = "popularity_percentile")
    private Double popularityPercentile;

    /** 跳转链接(非音频直链) */
    @Column(name = "play_url", length = 500)
    private String playUrl;

    /** 打分规则引擎给出的分数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer score = 0;

    /** 该版本判断的置信度 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Confidence confidence = Confidence.LOW;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private LocalDateTime fetchedAt;

    @PrePersist
    void onCreate() {
        if (fetchedAt == null) {
            fetchedAt = LocalDateTime.now();
        }
    }
}
