package org.example.musictraceback.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.example.musictraceback.application.PlaylistInjectionService;
import org.example.musictraceback.application.UserConfirmationService;
import org.example.musictraceback.domain.entity.PlaylistEntry;
import org.example.musictraceback.domain.entity.Snapshot;
import org.example.musictraceback.domain.enums.VersionType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌单相关 REST 接口(前端 Ajax 调用)。
 *
 * POST /api/playlists/import  — 注入歌单(链接/ID)
 * GET  /api/playlists/{id}    — 歌单详情(条目列表)
 * GET  /api/playlists/{id}/tracks/{entryId} — 单曲详情
 * POST /api/playlists/tracks/{entryId}/confirm — 用户确认版本
 */
@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistInjectionService injectionService;
    private final UserConfirmationService confirmationService;

    public PlaylistController(PlaylistInjectionService injectionService,
                              UserConfirmationService confirmationService) {
        this.injectionService = injectionService;
        this.confirmationService = confirmationService;
    }

    /** 入参校验 DTO */
    public record ImportRequest(@NotBlank(message = "歌单链接不能为空") String url) {
    }

    /** 注入歌单(同步执行;大批量歌单的异步进度在后续版本引入) */
    @PostMapping("/import")
    public ResponseEntity<?> importPlaylist(@Valid @RequestBody ImportRequest request) {
        try {
            Snapshot snapshot = injectionService.inject(request.url());
            if ("FAILED".equals(snapshot.getStatus())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(Map.of("error", "歌单解析失败,请检查链接是否有效"));
            }
            return ResponseEntity.ok(toSummary(snapshot));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "注入失败: " + e.getMessage()));
        }
    }

    /** 歌单详情(条目 + 状态 + 置信度) */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPlaylist(@PathVariable Long id) {
        return injectionService.findDetail(id)
                .map(s -> ResponseEntity.ok(toDetail(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 单曲详情(含该条目关联身份与 Top 版本) */
    @GetMapping("/{id}/tracks/{entryId}")
    public ResponseEntity<?> getTrack(@PathVariable Long id, @PathVariable Long entryId) {
        Snapshot snapshot = injectionService.findDetail(id).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        PlaylistEntry entry = snapshot.getPlaylistEntries().stream()
                .filter(e -> e.getId().equals(entryId))
                .findFirst()
                .orElse(null);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toTrackDetail(entry));
    }

    /** 用户确认请求体 */
    public record ConfirmRequest(
            @NotNull(message = "versionType 不能为空") VersionType versionType,
            Long canonicalTrackId,
            String chosenPlatformTrackId
    ) {
    }

    /** 用户确认版本(第 4 层兜底) */
    @PostMapping("/tracks/{entryId}/confirm")
    public ResponseEntity<?> confirmTrack(@PathVariable Long entryId,
                                          @Valid @RequestBody ConfirmRequest request) {
        try {
            var confirmation = confirmationService.confirm(
                    entryId, request.canonicalTrackId(), request.chosenPlatformTrackId(), request.versionType());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", confirmation.getId());
            m.put("playlistEntryId", entryId);
            m.put("versionType", confirmation.getChosenVersionType());
            m.put("confirmedAt", confirmation.getConfirmedAt());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** ===== DTO 组装(避免直接序列化 JPA 实体) ===== */

    private Map<String, Object> toSummary(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("platform", s.getPlatform());
        m.put("title", s.getTitle());
        m.put("status", s.getStatus());
        m.put("createdAt", s.getCreatedAt());
        m.put("entryCount", s.getPlaylistEntries().size());
        return m;
    }

    private Map<String, Object> toDetail(Snapshot s) {
        Map<String, Object> m = toSummary(s);
        m.put("entries", s.getPlaylistEntries().stream().map(this::toEntrySummary).toList());
        return m;
    }

    private Map<String, Object> toEntrySummary(PlaylistEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("platformTrackId", e.getPlatformTrackId());
        m.put("title", e.getRawTitle());
        m.put("artist", e.getRawArtist());
        m.put("status", e.getEntryStatus());
        m.put("confidence", e.getConfidence());
        m.put("versionType", e.getJudgedVersionType());
        if (e.getCanonicalTrack() != null) {
            m.put("canonicalTrackId", e.getCanonicalTrack().getId());
        }
        return m;
    }

    private Map<String, Object> toTrackDetail(PlaylistEntry e) {
        Map<String, Object> m = toEntrySummary(e);
        if (e.getCanonicalTrack() != null) {
            List<Map<String, Object>> versions = e.getCanonicalTrack().getPlatformVersions().stream()
                    .map(v -> {
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("platform", v.getPlatform());
                        vm.put("title", v.getTitle());
                        vm.put("artist", v.getArtist());
                        vm.put("album", v.getAlbum());
                        vm.put("versionType", v.getVersionType());
                        vm.put("popularityRaw", v.getPopularityRaw());
                        vm.put("popularityPercentile", v.getPopularityPercentile());
                        vm.put("playUrl", v.getPlayUrl());
                        vm.put("score", v.getScore());
                        vm.put("confidence", v.getConfidence());
                        return vm;
                    }).toList();
            m.put("versions", versions);
        }
        return m;
    }
}
