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
 * Created: 2026-08-06 15:50:00
 * Description: 基于 Protostuff 的对象序列化/反序列化工具：Schema 按类缓存，LinkedBuffer 线程内复用
 */
public final class ProtostuffSerializer {

    /** 按目标类缓存的 RuntimeSchema，computeIfAbsent 保证每个类只创建一次 */
    private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

    // LinkedBuffer 非线程安全，按线程隔离
    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    /** 工具类，禁止实例化 */
    private ProtostuffSerializer() {
    }

    /** 序列化对象为字节数组；obj 为 null 返回 null。 */
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
            // 复用前必须清空，避免残留上次数据
            buffer.clear();
        }
    }

    /** 反序列化字节为指定类型；空数据按无参构造返回新实例。 */
    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        if (clazz == null) {
            throw new ProtocolException("反序列化目标类型不能为空");
        }
        if (data == null || data.length == 0) {
            // 空 body 表示无内容，按空协议对象处理
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

    /** 按类取或创建并缓存 RuntimeSchema。 */
    @SuppressWarnings("unchecked")
    private static <T> Schema<T> schemaOf(Class<T> clazz) {
        return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(clazz, RuntimeSchema::createFrom);
    }
}
