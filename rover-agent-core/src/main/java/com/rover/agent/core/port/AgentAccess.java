package com.rover.agent.core.port;

import java.util.Set;

/** 宿主提供的实时权限判定；后台执行同样检查，不能只依赖 HTTP 页面或旧登录态。 */
public interface AgentAccess {
    enum Permission { READ, INVESTIGATE, PROPOSE, APPROVE, INGEST, POLICY_ADMIN, MANAGE_SYSTEM }

    Set<Permission> permissions(String principal);

    default void require(String principal, Permission permission) {
        if (!permissions(principal).contains(permission)) {
            throw new Denied("当前身份没有 " + permission + " 权限，或资源范围已变化");
        }
    }

    /** 本地领域测试/无认证宿主的兼容实现；Admin 必须注入实际权限服务。 */
    static AgentAccess local() {
        return new AgentAccess() {
            public Set<Permission> permissions(String principal) { return Set.of(Permission.values()); }
            public boolean managed() { return false; }
        };
    }

    default boolean managed() { return true; }

    /** 执行入口在提交前重新调用；宿主可增加持久写停开关和存储健康检查。 */
    default boolean writeAllowed(String principal) { return permissions(principal).contains(Permission.APPROVE); }

    /** Only the durable worker may use an explicit, currently matching preauthorization. */
    default boolean automatedWriteAllowed() { return false; }

    /** Atomically admits one bounded external write; implementations may reject after emergency stop. */
    default WritePermit acquireWritePermit(String principal, boolean automated) {
        return (automated ? automatedWriteAllowed() : writeAllowed(principal)) ? () -> { } : null;
    }

    @FunctionalInterface
    interface WritePermit extends AutoCloseable {
        @Override void close();
    }

    final class Denied extends RuntimeException {
        public Denied(String message) { super(message); }
    }
}
