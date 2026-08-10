/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 服务变更推送包
 *
 * 实现「服务变更 → 订阅客户端」的推送链路：
 * {@link SubscriptionManager} 维护 serviceName-group-channel 三级订阅关系（并发安全）；
 * {@link PushService} 在注册表发生变更后，把携带 revision 的全量快照
 * （SNAPSHOT 类型）推送给所有相关订阅连接，客户端据此做去重与增量更新。</p>
 */
package com.rover.nameserver.core.push;