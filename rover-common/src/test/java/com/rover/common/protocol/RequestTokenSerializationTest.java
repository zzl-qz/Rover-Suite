package com.rover.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.common.codec.ProtostuffSerializer;
import org.junit.jupiter.api.Test;

class RequestTokenSerializationTest {

    @Test
    void preservesTokensForAllAuthenticatedRequestTypes() {
        HeartbeatRequest heartbeat = new HeartbeatRequest();
        heartbeat.setToken("secret");
        QueryRequest query = new QueryRequest();
        query.setToken("secret");
        UnsubscribeRequest unsubscribe = new UnsubscribeRequest();
        unsubscribe.setToken("secret");

        assertEquals("secret", roundTrip(heartbeat, HeartbeatRequest.class).getToken());
        assertEquals("secret", roundTrip(query, QueryRequest.class).getToken());
        assertEquals("secret", roundTrip(unsubscribe, UnsubscribeRequest.class).getToken());
    }

    private static <T> T roundTrip(T value, Class<T> type) {
        return ProtostuffSerializer.deserialize(ProtostuffSerializer.serialize(value), type);
    }
}
