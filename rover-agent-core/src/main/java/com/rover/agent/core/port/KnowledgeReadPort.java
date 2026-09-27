package com.rover.agent.core.port;

import java.util.List;

/**
 * 知识检索端口：按问题检索运维知识，回答「怎么配置 / 怎么接入 / 怎么排查」类问题。
 *
 * 与只读数据端口（路由、实例、指标…）同属只读端口，业务只依赖本接口；
 * 实现可以是内存 FAQ、落盘知识库或向量检索，由运行/管理侧提供。
 */
public interface KnowledgeReadPort {

    /** 按问题文本检索相关条目，按相关度降序，最多 {@code limit} 条；{@code limit<=0} 时按实现默认。 */
    List<KnowledgeEntry> search(String query, int limit);
}
