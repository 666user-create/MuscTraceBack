package org.example.musictraceback.application;

import org.example.musictraceback.domain.entity.CanonicalTrack;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.VersionType;
import org.example.musictraceback.infrastructure.repository.PlaylistEntryRepository;
import org.example.musictraceback.infrastructure.repository.UserConfirmationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserConfirmationService 测试:确认落库、条目回写、错误路径。
 */
class UserConfirmationServiceTests {

    @Test
    void confirm_persistsAndUpdatesEntry() {
        UserConfirmationRepository confirmRepo = mock(UserConfirmationRepository.class);
        PlaylistEntryRepository entryRepo = mock(PlaylistEntryRepository.class);

        Snapshot snapshot = Snapshot.builder().platform("netease").status("COMPLETED").build();
        CanonicalTrack track = CanonicalTrack.builder()
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        PlaylistEntry entry = PlaylistEntry.builder()
                .snapshot(snapshot).rawTitle("晴天").rawArtist("周杰伦")
                .canonicalTrack(track)
                .confidence(Confidence.LOW).build();

        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));
        when(confirmRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(entryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var service = new UserConfirmationService(confirmRepo, entryRepo);
        var saved = service.confirm(1L, track.getId(), "2001", VersionType.ORIGINAL);

        assertThat(saved.getChosenVersionType()).isEqualTo(VersionType.ORIGINAL);
        assertThat(saved.getChosenPlatformTrackId()).isEqualTo("2001");
        // 条目置信度提升为 HIGH
        assertThat(entry.getConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(entry.getJudgedVersionType()).isEqualTo(VersionType.ORIGINAL);
        verify(confirmRepo, times(1)).save(any());
        verify(entryRepo, times(1)).save(entry);
    }

    @Test
    void confirm_entryNotFound_throws() {
        UserConfirmationRepository confirmRepo = mock(UserConfirmationRepository.class);
        PlaylistEntryRepository entryRepo = mock(PlaylistEntryRepository.class);
        when(entryRepo.findById(999L)).thenReturn(Optional.empty());

        var service = new UserConfirmationService(confirmRepo, entryRepo);
        assertThatThrownBy(() -> service.confirm(999L, null, null, VersionType.ORIGINAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verify(confirmRepo, never()).save(any());
    }

    @Test
    void confirm_mismatchedIdentity_throws() {
        UserConfirmationRepository confirmRepo = mock(UserConfirmationRepository.class);
        PlaylistEntryRepository entryRepo = mock(PlaylistEntryRepository.class);

        CanonicalTrack track = CanonicalTrack.builder()
                .id(1L)
                .normalizedTitle("晴天").normalizedArtist("周杰伦").build();
        PlaylistEntry entry = PlaylistEntry.builder()
                .snapshot(Snapshot.builder().build())
                .canonicalTrack(track)
                .confidence(Confidence.MEDIUM).build();
        when(entryRepo.findById(1L)).thenReturn(Optional.of(entry));

        var service = new UserConfirmationService(confirmRepo, entryRepo);
        assertThatThrownBy(() -> service.confirm(1L, 999L, null, VersionType.ORIGINAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
    }
}
