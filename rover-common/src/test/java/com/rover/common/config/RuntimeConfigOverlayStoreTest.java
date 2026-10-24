package com.rover.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeConfigOverlayStoreTest {
    @TempDir Path directory;

    @Test
    void replacingOverlayKeepsCompleteJsonAndLeavesNoTemporaryFiles() throws Exception {
        Path overlay = directory.resolve("runtime-config.json");
        RuntimeConfigOverlayStore store = new RuntimeConfigOverlayStore(overlay);

        store.save(Map.of("trace.sampleRate", "1", "metrics.enabled", "true"));
        assertEquals(Map.of("trace.sampleRate", "1", "metrics.enabled", "true"), store.load());

        store.save(Map.of("trace.sampleRate", "0"));
        assertEquals(Map.of("trace.sampleRate", "0"), store.load());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
