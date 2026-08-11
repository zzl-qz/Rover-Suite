/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 定义 nameserver 服务注册管理包
 *
 * 注册表核心：{@link ServiceRegistry} 是注册/注销/心跳/查询的抽象接口，
 * {@link InMemoryServiceRegistry} 为当前单机实现（双层 ConcurrentHashMap +
 * 服务级 revision 版本号），{@link RegistrySnapshot} 描述一次变更后的服务快照，
 * 是变更推送的数据载体。
 */
package com.rover.nameserver.core.registry;