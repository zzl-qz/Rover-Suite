package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-07 10:15:00
 * Description: 通用响应体：几乎所有请求（注册/注销/心跳等）共用，含集群预留字段
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
    private byte appliedAckMode = AckMode.SINGLE.getCode();
    /** 已确认副本数，单机为 1 */
    private int replicaAcked = 1;
    /** 服务版本号 */
    private long revision;
    /** Nameserver 进程启动世代（UUID），集群/客户端对齐用 */
    private String epoch;
    /** leader 提示，集群预留 */
    private String leaderHint;
    /** 处理节点 ID */
    private String nodeId;

    /** 成功响应：code=200、message=OK。 */
    public static CommonResponseBody success() {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", null);
    }

    /** 成功响应并携带附加数据。 */
    public static CommonResponseBody success(byte[] data) {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", data);
    }

    /** 失败响应：指定状态码与原因文案。 */
    public static CommonResponseBody fail(int code, String message) {
        return of(code, message, null);
    }

    /** 通用装配入口：填基础字段并用默认 ack（单机语义）初始化。 */
    public static CommonResponseBody of(int code, String message, byte[] data) {
        CommonResponseBody body = new CommonResponseBody();
        body.setCode(code);
        body.setMessage(message);
        body.setData(data);
        body.setAppliedAckMode(AckMode.SINGLE.getCode());
        body.setReplicaAcked(1);
        return body;
    }

    /** 覆写确认语义（集群场景用）；ackMode 为 null 回落 SINGLE。 */
    public CommonResponseBody withAck(AckMode ackMode, int replicaAcked) {
        this.appliedAckMode = ackMode == null ? AckMode.SINGLE.getCode() : ackMode.getCode();
        this.replicaAcked = replicaAcked;
        return this;
    }
}
