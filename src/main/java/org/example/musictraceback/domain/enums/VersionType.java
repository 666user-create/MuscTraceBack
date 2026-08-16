package org.example.musictraceback.domain.enums;

/**
 * 版本类型(设计文档核心概念"版本")
 */
public enum VersionType {
    /** 原版 */
    ORIGINAL,
    /** 现场版 */
    LIVE,
    /** 翻唱 */
    COVER,
    /** 重混/Remix */
    REMIX,
    /** 伴奏/纯音乐 */
    INSTRUMENTAL,
    /** 其他 */
    OTHER
}
