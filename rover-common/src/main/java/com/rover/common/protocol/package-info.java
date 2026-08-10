/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: TCP 协议相关模型
 *
 * 包职责：Rover TCP 二进制协议的数据模型集中地。
 * 包含：帧模型 RoverMessage、帧标志位工具 ProtocolFlags、确认模式 AckMode、
 * 以及注册/注销/心跳/查询/订阅/取消订阅等业务请求体与响应体。
 * 使用者：协议编解码器与 rover-client、rover-registry 的业务处理层。
 */
package com.rover.common.protocol;
