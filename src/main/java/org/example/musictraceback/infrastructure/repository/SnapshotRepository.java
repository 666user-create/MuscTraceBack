package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {

    List<Snapshot> findByPlatformOrderByCreatedAtDesc(String platform);

    List<Snapshot> findByStatusOrderByCreatedAtDesc(String status);
}
