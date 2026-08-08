package com.rover.common.constants;

public final class ProtocolConstants {

    private ProtocolConstants() {
    }

    public static final int MAGIC_NUMBER = 0xCAFE;

    public static final int VERSION = 1;

    public static final byte REGISTER_REQUEST = 1;
    public static final byte UNREGISTER_REQUEST = 2;
    public static final byte HEARTBEAT_REQUEST = 3;
    public static final byte QUERY_REQUEST = 4;
    public static final byte PUSH_RESPONSE = 5;
}
