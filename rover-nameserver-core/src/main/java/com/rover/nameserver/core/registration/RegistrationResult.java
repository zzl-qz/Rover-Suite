package com.rover.nameserver.core.registration;

import com.rover.nameserver.core.registry.RegistrySnapshot;
import java.util.Objects;

/**
 * Author: Daylight
 * Created: 2026-08-17 00:00:00
 * Description: 注册生命周期操作结果，显式区分数据变更、幂等续租、不存在和会话所有权冲突
 */
public final class RegistrationResult {

    private final Status status;
    private final RegistrySnapshot snapshot;
    private final long revision;

    private RegistrationResult(Status status, RegistrySnapshot snapshot, long revision) {
        this.status = Objects.requireNonNull(status, "status");
        this.snapshot = snapshot;
        this.revision = revision;
    }

    public static RegistrationResult changed(RegistrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new RegistrationResult(Status.CHANGED, snapshot, snapshot.getRevision());
    }

    public static RegistrationResult unchanged(long revision) {
        return new RegistrationResult(Status.UNCHANGED, null, revision);
    }

    public static RegistrationResult notFound(long revision) {
        return new RegistrationResult(Status.NOT_FOUND, null, revision);
    }

    public static RegistrationResult ownerMismatch(long revision) {
        return new RegistrationResult(Status.OWNER_MISMATCH, null, revision);
    }

    public Status status() {
        return status;
    }

    /** 仅数据面发生变化时非 null，调用方可据此推送一次新快照。 */
    public RegistrySnapshot snapshot() {
        return snapshot;
    }

    public long revision() {
        return revision;
    }

    /** CHANGED 和 UNCHANGED 都表示本次请求已被当前 owner 正常接受。 */
    public boolean isAccepted() {
        return status == Status.CHANGED || status == Status.UNCHANGED;
    }

    public boolean isChanged() {
        return status == Status.CHANGED;
    }

    public boolean isNotFound() {
        return status == Status.NOT_FOUND;
    }

    public boolean isOwnerMismatch() {
        return status == Status.OWNER_MISMATCH;
    }

    public enum Status {
        CHANGED,
        UNCHANGED,
        NOT_FOUND,
        OWNER_MISMATCH
    }
}
