package org.example.musictraceback.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 歌单快照:一次注入产生一个快照(设计文档 SNAPSHOT 表)
 */
@Entity
@Table(name = "snapshot")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源平台:netease / qq / kugou */
    @Column(nullable = false, length = 20)
    private String platform;

    /** 用户粘贴的歌单分享链接 */
    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    /** 歌单标题(解析得到) */
    @Column(length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 处理状态:PROCESSING / COMPLETED / FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PlaylistEntry> playlistEntries = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** 便捷方法:添加条目并维护双向关系 */
    public void addEntry(PlaylistEntry entry) {
        playlistEntries.add(entry);
        entry.setSnapshot(this);
    }
}
