/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 核心数据模型包
 *
 * 放置 Nameserver 服务端内部使用的数据模型。目前核心是
 * {@link InstanceRecord}——注册表中一条实例记录，将对外可见的
 * {@code ServiceInstance} 与内部的心跳时间字段绑定在一起，
 * 供注册表、健康检查使用。
 */
package com.rover.nameserver.core.model;