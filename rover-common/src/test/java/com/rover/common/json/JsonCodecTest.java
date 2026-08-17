package com.rover.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JsonCodecTest {

    @Test
    void parsesJsonObject() {
        Sample sample = JsonCodec.fromJson("{\"name\":\"rover\",\"port\":8889}", Sample.class);

        assertEquals("rover", sample.name);
        assertEquals(8889, sample.port);
    }

    @Test
    void rejectsBlankAndMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.fromJson(" ", Sample.class));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.fromJson("null", Sample.class));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.fromJson("{", Sample.class));
    }

    public static class Sample {
        public String name;
        public int port;
    }
}
