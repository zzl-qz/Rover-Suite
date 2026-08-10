/**
 * Author: Daylight
 * Created: 2026-08-08 11:37:00
 * Description: 定义 Rover 通用配置模型和变更事件包
 *
 * 包职责：提供配置管理所需的统一模型与契约。
 * 包含：配置项模型 ConfigItem、生效方式枚举 ConfigApplyMode、
 * 配置变更事件 ConfigChangeEvent、组件运行时配置契约 RuntimeConfigManager、
 * 以及运行期配置落盘存储 RuntimeConfigOverlayStore。
 * 使用者：rover-admin 管理端与各组件(网关/注册中心)的配置实现。
 */
package com.rover.common.config;
