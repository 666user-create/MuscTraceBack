package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CanonicalTrackRepository extends JpaRepository<CanonicalTrack, Long> {

    Optional<CanonicalTrack> findByNormalizedTitleAndNormalizedArtist(
            String normalizedTitle, String normalizedArtist);
}
