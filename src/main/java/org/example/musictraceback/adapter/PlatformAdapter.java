package org.example.musictraceback.adapter;

import org.example.musictraceback.adapter.dto.PlaylistEntryRaw;
import org.example.musictraceback.adapter.dto.TrackCandidate;

import java.util.List;

/**
 * 平台适配器统一接口(设计文档 8.2 节)
 * 新增平台 = 新增一个实现类,不改核心逻辑。
 *
 * 注意:各平台接口返回结构差异(如网易云搜索返回 artists/duration,
 * 歌单返回 ar/dt),由各实现内部处理,对外统一为本接口的 DTO。
 */
public interface PlatformAdapter {

    /** 平台标识:netease / qq / kugou */
    String platformId();

    /**
     * 解析歌单分享链接或 ID,返回原始曲目列表。
     *
     * @param shareUrlOrId 分享链接或歌单 ID
     * @return 曲目原始信息(未规范化)
     */
    List<PlaylistEntryRaw> parsePlaylist(String shareUrlOrId);

    /**
     * 按歌名+歌手搜索候选版本。
     *
     * @param title  规范化前的原始歌名
     * @param artist 规范化前的原始歌手
     * @return 候选版本列表(空列表 = 无结果)
     */
    List<TrackCandidate> search(String title, String artist);

    /**
     * 获取歌曲详情(时长/专辑/热度等)。
     *
     * @param trackId 平台内歌曲 ID
     * @return 详情;查不到返回 null
     */
    TrackCandidate detail(String trackId);

    /**
     * 生成可跳转的播放页链接(非音频直链,合规)。
     */
    String playUrl(String trackId);
}
