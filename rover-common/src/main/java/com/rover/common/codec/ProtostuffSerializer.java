package com.rover.common.codec;

import com.rover.common.exception.ProtocolException;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:30:00
 * Description: Protostuff 序列化工具
 *
 * 这个类是什么：基于 Protostuff 的对象序列化/反序列化工具类。
 * 核心职责：RuntimeSchema 按类缓存；LinkedBuffer 用 ThreadLocal 线程内复用。
 * 被谁用：RoverMessageCodecSupport、NameserverClient.query、QueryListener 等。
 */
public final class ProtostuffSerializer {

    /** 按目标类缓存的 RuntimeSchema，computeIfAbsent 保证每个类只创建一次 */
    private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

    // LinkedBuffer 不能并发用，搞个线程本地的
    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    /** 工具类，禁止实例化 */
    private ProtostuffSerializer() {
    }

    /**
     * 将对象序列化为字节数组。
     *
     * @param obj 待序列化对象；目标类需有无参构造且字段可公开访问（Protostuff 约束）
     * @return 序列化后的字节数组；obj 为 null 时返回 null
     * @throws ProtocolException 序列化过程出错时抛出，包装底层异常
     */
    @SuppressWarnings("unchecked")
    public static <T> byte[] serialize(T obj) {
        if (obj == null) {
            return null;
        }
        Class<T> clazz = (Class<T>) obj.getClass();
        Schema<T> schema = schemaOf(clazz);
        // 从 ThreadLocal 取出本线程专属 buffer 复用，避免 LinkedBuffer 并发写坏
        LinkedBuffer buffer = BUFFER.get();
        try {
            return ProtostuffIOUtil.toByteArray(obj, schema, buffer);
        } catch (Exception ex) {
            throw new ProtocolException("Protostuff 序列化失败: " + clazz.getName(), ex);
        } finally {
            // 不清会脏
            buffer.clear();
        }
    }

    /**
     * 将字节数组反序列化为指定类型对象。
     *
     * @param data  待反序列化的字节；null 或空数组时走"空对象"分支（用无参构造返回新实例）
     * @param clazz 目标类型，不能为 null
     * @return 反序列化得到的对象
     * @throws ProtocolException clazz 为 null、无法创建空对象或反序列化失败时抛出
     */
    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        if (clazz == null) {
            throw new ProtocolException("反序列化目标类型不能为空");
        }
        if (data == null || data.length == 0) {
            // 空 body 表示无内容，按空协议对象处理，避免对空数组解析
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new ProtocolException("创建空协议对象失败: " + clazz.getName(), ex);
            }
        }
        try {
            Schema<T> schema = schemaOf(clazz);
            // 先按 schema 建新对象，再把字节合并进去
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data, message, schema);
            return message;
        } catch (ProtocolException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProtocolException("Protostuff 反序列化失败: " + clazz.getName(), ex);
        }
    }

    /**
     * 按类名取或创建并缓存 RuntimeSchema。
     *
     * @param clazz 目标类型
     * @return 对应 Schema
     */
    @SuppressWarnings("unchecked")
    private static <T> Schema<T> schemaOf(Class<T> clazz) {
        return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(clazz, RuntimeSchema::createFrom);
    }
}
