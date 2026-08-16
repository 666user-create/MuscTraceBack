package org.example.musictraceback.adapter.dto;

/**
 * 平台返回的候选版本(统一结构)
 */
public record TrackCandidate(
        String platformTrackId,
        String title,
        String artist,
        String album,
        Integer durationSec,
        Long popularity,
        String playUrl
) {
}
