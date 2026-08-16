package org.example.musictraceback;

import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.Confidence;
import org.example.musictraceback.domain.enums.EntryStatus;
import org.example.musictraceback.infrastructure.repository.SnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1.5 数据层测试:验证实体映射与 Repository CRUD
 * 使用内存 H2(测试专用),不影响真实数据文件
 */
@DataJpaTest
class DataLayerTests {

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Test
    void saveSnapshotWithEntries_thenQueryBack() {
        // 构造快照 + 条目
        Snapshot snapshot = Snapshot.builder()
                .platform("netease")
                .sourceUrl("https://music.163.com/#/playlist?id=123")
                .title("我的收藏")
                .status("PROCESSING")
                .build();

        snapshot.addEntry(PlaylistEntry.builder()
                .platformTrackId("1001")
                .rawTitle("晴天")
                .rawArtist("周杰伦")
                .entryStatus(EntryStatus.NORMAL)
                .confidence(Confidence.HIGH)
                .build());
        snapshot.addEntry(PlaylistEntry.builder()
                .platformTrackId("1002")
                .rawTitle("七里香")
                .rawArtist("周杰伦")
                .entryStatus(EntryStatus.DELISTED)
                .confidence(Confidence.LOW)
                .build());

        // 保存(级联保存条目)
        Snapshot saved = snapshotRepository.save(snapshot);

        // 查回并断言
        Snapshot found = snapshotRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getPlatform()).isEqualTo("netease");
        assertThat(found.getTitle()).isEqualTo("我的收藏");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getPlaylistEntries()).hasSize(2);

        List<PlaylistEntry> entries = found.getPlaylistEntries().stream()
                .sorted((a, b) -> a.getPlatformTrackId().compareTo(b.getPlatformTrackId()))
                .toList();
        assertThat(entries.get(0).getRawTitle()).isEqualTo("晴天");
        assertThat(entries.get(0).getEntryStatus()).isEqualTo(EntryStatus.NORMAL);
        assertThat(entries.get(1).getEntryStatus()).isEqualTo(EntryStatus.DELISTED);
    }

    @Test
    void findByPlatform_returnsOnlyMatching() {
        Snapshot s1 = Snapshot.builder().platform("netease").status("COMPLETED").build();
        Snapshot s2 = Snapshot.builder().platform("qq").status("COMPLETED").build();
        snapshotRepository.save(s1);
        snapshotRepository.save(s2);

        List<Snapshot> netease = snapshotRepository.findByPlatformOrderByCreatedAtDesc("netease");
        assertThat(netease).hasSize(1);
        assertThat(netease.get(0).getPlatform()).isEqualTo("netease");
    }
}
