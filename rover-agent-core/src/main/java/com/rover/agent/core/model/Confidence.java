package com.rover.agent.core.model;

/** 结论置信度。 */
public enum Confidence {

    /** 事实充分，结论可直接采信 */
    HIGH,

    /** 事实部分充分，结论需要人工复核 */
    MEDIUM,

    /** 事实不足，结论仅作方向性提示 */
    LOW
}