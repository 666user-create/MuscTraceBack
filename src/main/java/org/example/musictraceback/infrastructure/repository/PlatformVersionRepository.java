package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.PlatformVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformVersionRepository extends JpaRepository<PlatformVersion, Long> {

    List<PlatformVersion> findByCanonicalTrackId(Long canonicalTrackId);

    List<PlatformVersion> findByCanonicalTrackIdOrderByScoreDesc(Long canonicalTrackId);

    /** 全局去重:该平台曲目是否已被任何身份收录(避免唯一约束冲突) */
    boolean existsByPlatformAndPlatformTrackId(String platform, String platformTrackId);
}
