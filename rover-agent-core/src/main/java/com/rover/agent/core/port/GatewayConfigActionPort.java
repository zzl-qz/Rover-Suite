package com.rover.agent.core.port;

/** Narrow port for the allowlisted, durable Gateway runtime configuration action API. */
public interface GatewayConfigActionPort {

    /** Submit one immutable intent using an operation ID that was persisted by the caller first. */
    GatewayConfigActionReceipt execute(String operationId, String configKey, String expectedValue, String desiredValue);

    /** Read the durable receipt for an existing operation; this method must never submit a write. */
    GatewayConfigActionReceipt query(String operationId);
}
