/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: 协议编解码
 *
 * 包职责：客户端侧的协议编解码实现——RoverMessageEncoder/Decoder 负责 Netty
 * 出入站字节帧与 RoverMessage 的互转（含粘包半包处理、魔数/版本/flags/长度校验），
 * ProtostuffSerializer 提供消息体的 Protostuff 序列化，RoverMessageCodecSupport
 * 提供组装各类请求/响应/推送消息的快捷方法。
 */
package com.rover.nameserver.client.codec;