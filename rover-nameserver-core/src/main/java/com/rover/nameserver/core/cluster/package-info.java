/**
 * 集群相关扩展点（当前以单机实现为主）。
 *
 * 已预留：{@link NameserverGeneration} 提供 epoch/term/leaderHint。
 * 上集群时优先替换 Generation 实现与写路径（Leader 推送），
 * 而不是在 PushService 里写死 UUID。
 */
package com.rover.nameserver.core.cluster;
