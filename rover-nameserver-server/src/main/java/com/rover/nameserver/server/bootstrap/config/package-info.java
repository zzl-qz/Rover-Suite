/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: 定义 Nameserver 启动配置加载包
 *
 * 提供启动配置的数据模型与加载能力：{@link NameserverConfig} 是
 * rover-nameserver.yml 的强类型映射（含默认值与派生方法），
 * {@link NameserverConfigLoader} 按「外部 config/ 优先、classpath 兜底、
 * 全缺失走默认」读取配置。
 */
package com.rover.nameserver.server.bootstrap.config;