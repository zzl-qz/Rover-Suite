/**
 * Author: Daylight
 * Created: 2026-09-26 10:00:00
 * Description: Agent 存储实现：{@code JdbcAgentStore} 把会话 / 事件 / 任务 / 步骤 / 证据落到关系表（重启后可恢复），
 *              另有一套线程安全内存实现，作为未配记录库路径时的退路
 */
package com.rover.agent.runtime.repository;
