package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.PlatformVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformVersionRepository extends JpaRepository<PlatformVersion, Long> {

    List<PlatformVersion> findByCanonicalTrackId(Long canonicalTrackId);

    List<PlatformVersion> findByCanonicalTrackIdOrderByScoreDesc(Long canonicalTrackId);
}
