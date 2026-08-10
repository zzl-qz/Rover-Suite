/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 客户端服务缓存包
 *
 * 包职责：维护服务实例的本地缓存（InstanceCache），承载查询快照的写入、
 * 服务端推送的落地与变更监听，供连接模块（connection）与上层业务使用。
 */
package com.rover.nameserver.client.cache;
