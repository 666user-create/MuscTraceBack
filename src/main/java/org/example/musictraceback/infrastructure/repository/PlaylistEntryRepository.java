package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaylistEntryRepository extends JpaRepository<PlaylistEntry, Long> {

    List<PlaylistEntry> findBySnapshotId(Long snapshotId);

    /** 未处理的条目(断点续跑/异步进度) */
    List<PlaylistEntry> findBySnapshotIdAndProcessedFalse(Long snapshotId);

    /** 快照内已处理条目数(进度) */
    long countBySnapshotIdAndProcessedTrue(Long snapshotId);

    /** 快照内条目总数 */
    long countBySnapshotId(Long snapshotId);
}
