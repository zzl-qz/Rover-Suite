package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: nameserver 内部 RPC 协议对象
 */
@Data
public class CommonResponseBody {

    private int code;
    private String message;
    private byte[] data;

    private byte appliedAckMode = AckMode.IMMEDIATE.getCode();

    // 单机先写 1，集群再填真实确认数
    private int replicaAcked = 1;

    private long revision;

    // 集群非 leader 时可以捎一句该去哪
    private String leaderHint;

    private String nodeId;

    public static CommonResponseBody success() {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", null);
    }

    public static CommonResponseBody success(byte[] data) {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", data);
    }

    public static CommonResponseBody fail(int code, String message) {
        return of(code, message, null);
    }

    public static CommonResponseBody of(int code, String message, byte[] data) {
        CommonResponseBody body = new CommonResponseBody();
        body.setCode(code);
        body.setMessage(message);
        body.setData(data);
        body.setAppliedAckMode(AckMode.IMMEDIATE.getCode());
        body.setReplicaAcked(1);
        return body;
    }

    public CommonResponseBody withAck(AckMode ackMode, int replicaAcked) {
        this.appliedAckMode = ackMode == null ? AckMode.IMMEDIATE.getCode() : ackMode.getCode();
        this.replicaAcked = replicaAcked;
        return this;
    }
}
