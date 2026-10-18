package com.rover.demo.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReplayControllerTest {

    @Test
    void independentVersionsCanFailAndRecoverWithoutRestarting() {
        ReplayController v1 = new ReplayController("v1", "fixture-token");
        ReplayController v2 = new ReplayController("v2", "fixture-token");
        assertEquals(200, v1.order().getStatusCode().value());
        v2.configure("fixture-token", new ReplayController.Fault(503, 0));
        assertEquals(503, v2.order().getStatusCode().value());
        assertEquals("v2", v2.order().getHeaders().getFirst("X-Rover-Replay-Version"));
        assertEquals(200, v1.order().getStatusCode().value());
        v2.configure("fixture-token", new ReplayController.Fault(200, 0));
        assertEquals(200, v2.order().getStatusCode().value());
    }

    @Test
    void invalidAndUnauthorizedFaultsNeverChangeTheCurrentState() {
        ReplayController controller = new ReplayController("v2", "fixture-token");
        assertThrows(IllegalArgumentException.class, () -> new ReplayController.Fault(302, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReplayController.Fault(503, -1));
        assertThrows(IllegalArgumentException.class, () -> new ReplayController.Fault(200, 2001));
        assertThrows(IllegalArgumentException.class, () -> new ReplayController("v2", ""));
        assertThrows(ResponseStatusException.class,
                () -> controller.configure("wrong-token", new ReplayController.Fault(503, 0)));
        assertThrows(ResponseStatusException.class, () -> controller.state(null));
        assertEquals(new ReplayController.Fault(200, 0), controller.state("fixture-token"));
    }
}
