package com.rover.common.event;

public interface EventListener<T> {

    void onEvent(T event);
}
