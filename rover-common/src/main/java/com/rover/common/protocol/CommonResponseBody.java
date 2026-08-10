package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 通用响应 body
 */
@Data
public class CommonResponseBody {

    /** 状态码 */
    private int code;
    /** 说明文案 */
    private String message;
    /** 附加数据 */
    private byte[] data;
    /** 实际 ack 模式，集群预留 */
    private byte appliedAckMode = AckMode.IMMEDIATE.getCode();
    /** 已确认副本数，单机为 1 */
    private int replicaAcked = 1;
    /** 服务版本号 */
    private long revision;
    /** leader 提示，集群预留 */
    private String leaderHint;
    /** 处理节点 ID */
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
