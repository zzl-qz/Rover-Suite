package com.rover.common.protocol;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 通用响应 body
 *
 * 这个类是什么：几乎所有请求(注册/注销/心跳等)共用的一种响应体。
 * 核心职责：返回业务状态、文案、附带数据，并携带 ack 模式/副本确认数/版本号等
 * 集群预留字段，单机环境下这些字段取默认值即可。
 * 被谁用：注册中心服务端响应 COMMON_RESPONSE 消息；客户端解析应答。
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

    /**
     * 构造成功响应。
     *
     * @return code=200、message=OK、无数据
     */
    public static CommonResponseBody success() {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", null);
    }

    /**
     * 构造带数据的成功响应。
     *
     * @param data 附加数据
     * @return code=200、message=OK、带上 data
     */
    public static CommonResponseBody success(byte[] data) {
        return of(com.rover.common.constants.StatusConstants.SUCCESS, "OK", data);
    }

    /**
     * 构造失败响应。
     *
     * @param code    失败状态码，见 StatusConstants
     * @param message 失败原因文案
     * @return 无数据的应答体
     */
    public static CommonResponseBody fail(int code, String message) {
        return of(code, message, null);
    }

    /**
     * 通用装配入口。
     *
     * @param code    状态码
     * @param message 文案
     * @param data    附加数据
     * @return 填好基础字段并用默认 ack(单机语义)初始化的应答体
     */
    public static CommonResponseBody of(int code, String message, byte[] data) {
        CommonResponseBody body = new CommonResponseBody();
        body.setCode(code);
        body.setMessage(message);
        body.setData(data);
        body.setAppliedAckMode(AckMode.IMMEDIATE.getCode());
        body.setReplicaAcked(1);
        return body;
    }

    /**
     * 覆写确认语义(集群场景用)。
     *
     * @param ackMode      实际生效的 ack 模式；null 回落为 IMMEDIATE
     * @param replicaAcked 已确认副本数
     * @return this，便于链式调用
     */
    public CommonResponseBody withAck(AckMode ackMode, int replicaAcked) {
        this.appliedAckMode = ackMode == null ? AckMode.IMMEDIATE.getCode() : ackMode.getCode();
        this.replicaAcked = replicaAcked;
        return this;
    }
}
