package com.rover.agent.runtime.llm;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 只检查异常类型和结构化状态码；不解析或公开供应商响应正文。 */
public final class ModelFailures {
    private ModelFailures() { }
    public static String kind(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause=failure;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if (cause instanceof ProviderIsolation.Rejected rejected) { return rejected.kind(); }
            if (cause instanceof com.rover.agent.runtime.task.AgentRunBudget.LimitExceeded || cause instanceof java.util.concurrent.CancellationException) { return "CANCELLED"; }
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.io.InterruptedIOException || cause instanceof java.util.concurrent.TimeoutException) { return "TIMEOUT"; }
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException || cause instanceof java.net.NoRouteToHostException) { return "UNAVAILABLE"; }
            if (cause instanceof java.io.IOException) { return "UNAVAILABLE"; }
            Integer status=status(cause);
            if (status!=null) {
                if (status==401 || status==403) { return "AUTHENTICATION"; }
                if (status==429) { return "RATE_LIMITED"; }
                if (status>=500) { return "SERVER_ERROR"; }
                if (status>=400) { return "INVALID_REQUEST"; }
            }
            if (cause.getClass().getName().endsWith(".TransientAiException")) { return "UNAVAILABLE"; }
        }
        return "ERROR";
    }
    private static Integer status(Throwable failure) {
        if (failure instanceof ModelProviderFailure typed) { return typed.status(); }
        String name=failure.getClass().getName();
        if (!(name.startsWith("com.openai.errors.") || name.startsWith("org.springframework.web."))) { return null; }
        // SDK 版本不同但结构化状态接口稳定；运行层不依赖某个供应商 SDK 类型。
        for (String method:new String[]{"statusCode","getStatusCode"}) {
            try {
                Object status=failure.getClass().getMethod(method).invoke(failure);
                if (status instanceof Number value) { return value.intValue(); }
                if (status!=null) { return ((Number)status.getClass().getMethod("value").invoke(status)).intValue(); }
            } catch (ReflectiveOperationException | ClassCastException ignored) { }
        }
        return null;
    }

    public static int retryAfterSeconds(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null && seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof ModelProviderFailure typed) { return typed.retryAfterSeconds(); }
            String name=cause.getClass().getName();
            if(!(name.startsWith("org.springframework.web.") || name.startsWith("com.openai.errors."))) { continue; }
            for(String getter:new String[]{"getHeaders","headers"}) {
                try {
                    Object headers=cause.getClass().getMethod(getter).invoke(cause);
                    for(String method:new String[]{"getFirst","values"}) {
                        try {
                            Object value=headers.getClass().getMethod(method,String.class).invoke(headers,method.equals("values") ? "retry-after" : "Retry-After");
                            if(value instanceof java.util.List<?> values) { value=values.isEmpty() ? null : values.get(0); }
                            if(value instanceof String header) { return parseRetryAfter(header); }
                        } catch(ReflectiveOperationException ignored) { }
                    }
                } catch(ReflectiveOperationException | NullPointerException ignored) { }
            }
        }
        return 0;
    }

    public static int parseRetryAfter(String header) {
        if(header==null) { return 0; }
        try { return (int)Math.min(3600,Math.max(0,Long.parseLong(header.trim()))); }
        catch(NumberFormatException ignored) {
            try { return (int)Math.min(3600,Math.max(0,java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds())); }
            catch(java.time.format.DateTimeParseException invalid) { return 0; }
        }
    }
}
