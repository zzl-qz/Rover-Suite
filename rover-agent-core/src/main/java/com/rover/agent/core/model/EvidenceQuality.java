package com.rover.agent.core.model;

/** EMPTY 是已读取但无样本；UNAVAILABLE 是不能读取；二者不能互相替代。 */
public enum EvidenceQuality { AVAILABLE, EMPTY, PARTIAL, UNAVAILABLE, STALE, OUT_OF_RANGE }
