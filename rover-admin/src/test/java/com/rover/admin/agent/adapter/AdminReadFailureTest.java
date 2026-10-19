package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.admin.client.ManageApiCallException;
import com.rover.agent.core.capability.CapabilityResult;
import org.junit.jupiter.api.Test;

class AdminReadFailureTest {
    @Test void onlyTransientTransportAndHttpFailuresCanBeRetried() {
        for (int status : new int[]{408,429,502,503,504}) {
            assertTrue(CapabilityResult.transientFailure(AdminValues.unavailable("test", new ManageApiCallException(status,"test"))));
        }
        for (int status : new int[]{400,401,403,404,409,500}) {
            assertFalse(CapabilityResult.transientFailure(AdminValues.unavailable("test", new ManageApiCallException(status,"test"))));
        }
        assertTrue(CapabilityResult.transientFailure(AdminValues.unavailable("test",new ManageApiCallException(0,"test",new java.net.ConnectException()))));
        assertFalse(CapabilityResult.transientFailure(AdminValues.unavailable("test",new IllegalArgumentException("bad payload"))));
    }
}
