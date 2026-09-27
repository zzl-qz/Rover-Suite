package com.rover.agent.core.port;

import java.util.List;

/**
 * 知识库条目：一条可被检索的运维知识 / 文档片段。
 *
 * {@code keywords} 是命中判据（与标题、正文共同参与相关度评分），供轻量检索实现使用；
 * 换用向量检索时该字段退化为补充信息，不参与向量化。
 *
 * @param id       条目 ID
 * @param title    条目标题
 * @param content  正文内容（回答问题时引用它）
 * @param keywords 命中关键词，用于关键词检索
 */
public record KnowledgeEntry(String id, String title, String content, List<String> keywords) {

    public KnowledgeEntry {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
