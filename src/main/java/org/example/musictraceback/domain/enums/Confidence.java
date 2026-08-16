package org.example.musictraceback.domain.enums;

/**
 * 身份/版本判断的置信度(设计文档 9 节第 3 层)
 */
public enum Confidence {
    /** 高置信:自动认定 */
    HIGH,
    /** 中置信:疑似,需用户确认 */
    MEDIUM,
    /** 低置信:无法解析,人工兜底 */
    LOW
}
