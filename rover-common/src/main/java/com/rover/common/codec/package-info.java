/**
 * Author: Daylight
 * Created: 2026-08-13
 * Description: 协议编解码（两端共用）
 *
 * 包职责：TCP 协议帧与 RoverMessage 互转，以及 body 的 Protostuff 序列化。
 * 放 common 的原因：Nameserver 服务端 / 客户端 / 以后别的协议消费者都要同一套线格式，
 * 不能挂在 client 模块里让 core 倒挂依赖。
 */
package com.rover.common.codec;
