package com.rover.nameserver.client.codec;

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
 */
public final class ProtostuffSerializer {

    private static final Map<Class<?>, Schema<?>> SCHEMA_CACHE = new ConcurrentHashMap<>();

    // LinkedBuffer 不能并发用，搞个线程本地的
    private static final ThreadLocal<LinkedBuffer> BUFFER =
            ThreadLocal.withInitial(() -> LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));

    private ProtostuffSerializer() {
    }

    @SuppressWarnings("unchecked")
    public static <T> byte[] serialize(T obj) {
        if (obj == null) {
            return null;
        }
        Class<T> clazz = (Class<T>) obj.getClass();
        Schema<T> schema = schemaOf(clazz);
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

    public static <T> T deserialize(byte[] data, Class<T> clazz) {
        if (clazz == null) {
            throw new ProtocolException("反序列化目标类型不能为空");
        }
        if (data == null || data.length == 0) {
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new ProtocolException("创建空协议对象失败: " + clazz.getName(), ex);
            }
        }
        try {
            Schema<T> schema = schemaOf(clazz);
            T message = schema.newMessage();
            ProtostuffIOUtil.mergeFrom(data, message, schema);
            return message;
        } catch (ProtocolException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProtocolException("Protostuff 反序列化失败: " + clazz.getName(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Schema<T> schemaOf(Class<T> clazz) {
        return (Schema<T>) SCHEMA_CACHE.computeIfAbsent(clazz, RuntimeSchema::createFrom);
    }
}
