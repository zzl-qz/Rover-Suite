package com.rover.agent.core.port;

import com.rover.agent.core.snapshot.TraceSnapshot;

/** 只读读取 Gateway 请求追踪。 */
public interface TraceReadPort {

    /** 读取与某请求路径精确匹配的抽样追踪快照。不可用时抛 {@link SnapshotUnavailableException}。 */
    TraceSnapshot byPath(String path);
}