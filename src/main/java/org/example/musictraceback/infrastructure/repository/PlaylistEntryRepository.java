package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaylistEntryRepository extends JpaRepository<PlaylistEntry, Long> {

    List<PlaylistEntry> findBySnapshotId(Long snapshotId);
}
