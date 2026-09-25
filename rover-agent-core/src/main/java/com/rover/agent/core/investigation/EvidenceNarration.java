package com.rover.agent.core.investigation;

import java.util.List;

/**
 * 一条证据的表述结果。
 *
 * @param detail      写进证据列表的事实说明
 * @param limitations 由该事实带来的判断边界
 */
public record EvidenceNarration(String detail, List<String> limitations) { }