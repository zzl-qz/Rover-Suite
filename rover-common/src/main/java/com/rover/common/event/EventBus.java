package com.rover.common.event;

public interface EventBus {

    void publish(Event event);

    void subscribe(EventListener<? extends Event> listener);
}
