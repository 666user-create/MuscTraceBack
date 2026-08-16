package org.example.musictraceback.infrastructure.repository;

import org.example.musictraceback.domain.entity.UserConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserConfirmationRepository extends JpaRepository<UserConfirmation, Long> {

    List<UserConfirmation> findByPlaylistEntryId(Long playlistEntryId);
}
