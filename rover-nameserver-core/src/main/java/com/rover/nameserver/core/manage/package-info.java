/**
 * Author: Daylight
 * Created: 2026-08-10 16:45:00
 * Description: Nameserver HTTP 管理口
 *
 * 旁路 HTTP 管理接口：NameserverHttpManageServer 监听 managePort，
 * NameserverManageApi 提供 /_manage/status、/instances、/configs 等 REST 风格路由，
 * 供 Admin 或运维脚本查询状态与热更新配置。
 */
package com.rover.nameserver.core.manage;
