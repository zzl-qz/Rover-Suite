package com.rover.gateway.core.proxy;

import com.rover.common.constants.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;

/**
 * hop-by-hop / 网关自管转发头：预计算常量，比较走 contentEqualsIgnoreCase，热路径不 toLowerCase。
 */
final class HopByHopHeaders {

    private static final AsciiString[] HOP_BY_HOP = {
            HttpHeaderNames.CONNECTION,
            HttpHeaderNames.KEEP_ALIVE,
            HttpHeaderNames.PROXY_AUTHENTICATE,
            HttpHeaderNames.PROXY_AUTHORIZATION,
            HttpHeaderNames.TE,
            HttpHeaderNames.TRAILER,
            HttpHeaderNames.TRANSFER_ENCODING,
            HttpHeaderNames.UPGRADE,
            HttpHeaderNames.HOST,
            HttpHeaderNames.CONTENT_LENGTH,
            HttpHeaderNames.EXPECT,
            HttpHeaderNames.ACCEPT_ENCODING
    };

    private static final AsciiString[] MANAGED_FORWARD = {
            AsciiString.cached(HttpConstants.REQUEST_ID_HEADER),
            AsciiString.cached(HttpConstants.FORWARDED_FOR_HEADER),
            AsciiString.cached(HttpConstants.FORWARDED_HOST_HEADER),
            AsciiString.cached(HttpConstants.FORWARDED_PROTO_HEADER)
    };

    private HopByHopHeaders() {
    }

    static boolean isHopByHop(CharSequence name) {
        return matches(HOP_BY_HOP, name);
    }

    static boolean isManagedForward(CharSequence name) {
        return matches(MANAGED_FORWARD, name);
    }

    private static boolean matches(AsciiString[] names, CharSequence name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        for (AsciiString candidate : names) {
            if (candidate.contentEqualsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
