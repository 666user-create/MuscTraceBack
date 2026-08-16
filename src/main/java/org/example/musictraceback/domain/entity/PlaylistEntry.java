package org.example.musictraceback.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.EntryStatus;
import org.example.musictraceback.domain.enums.VersionType;

/**
 * 歌单快照中的单个条目(设计文档 PLAYLIST_ENTRY 表)
 */
@Entity
@Table(name = "playlist_entry")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PlaylistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private Snapshot snapshot;

    /** 源平台内歌曲 ID */
    @Column(name = "platform_track_id", length = 100)
    private String platformTrackId;

    /** 原始歌名(未规范化) */
    @Column(name = "raw_title", nullable = false, length = 300)
    private String rawTitle;

    /** 原始歌手(未规范化) */
    @Column(name = "raw_artist", nullable = false, length = 300)
    private String rawArtist;

    /** 关联的真实身份(可为 null=未解析) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canonical_track_id")
    private CanonicalTrack canonicalTrack;

    /** 曲目状态:NORMAL/DELISTED/REPLACED/OTHER_PLATFORM */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_status", nullable = false, length = 20)
    @Builder.Default
    private EntryStatus entryStatus = EntryStatus.NORMAL;

    /** 身份判断置信度:HIGH/MEDIUM/LOW */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private Confidence confidence = Confidence.LOW;

    /** 判定版本类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "judged_version_type", length = 20)
    @Builder.Default
    private VersionType judgedVersionType = VersionType.ORIGINAL;

    /** 平台返回的原始元数据(JSON 字符串,便于排查) */
    @Column(name = "raw_metadata_json", columnDefinition = "CLOB")
    private String rawMetadataJson;
}
