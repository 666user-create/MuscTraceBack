package org.example.musictraceback.adapter.dto;

/**
 * 歌单中的原始曲目条目(未规范化)
 */
public record PlaylistEntryRaw(
        String platformTrackId,
        String title,
        String artist,
        String album
) {
}
