package org.example.musictraceback.domain.enums;

/**
 * 歌单条目的曲目状态(设计文档 FR-5 / 核心概念)
 */
public enum EntryStatus {
    /** 正常可听 */
    NORMAL,
    /** 在源平台已下架 */
    DELISTED,
    /** 被平台替换成了其他版本(翻唱/Live 等) */
    REPLACED,
    /** 源平台不可听,但其他平台有可用版本 */
    OTHER_PLATFORM
}
