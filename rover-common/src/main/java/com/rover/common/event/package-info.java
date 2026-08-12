/**
 * Author: Daylight
 * Created: 2026-08-12 00:00:00
 * Description: 定义 Rover 进程内事件总线与事件模型包
 *
 * 包职责：提供事件解耦所需的基础模型与异步总线。
 * 包含：事件标记接口 Event、监听器回调 EventListener、异步事件总线 EventBus、
 * 以及具体事件 ServiceChangeEvent。
 * 使用者：监听器实现 EventListener 并经 SPI 登记，由 EventBus#init 自动加载；
 * 生产者调用 EventBus#publish 异步分发事件。
 */
package com.rover.common.event;
