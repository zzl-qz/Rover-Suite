package com.rover.gateway.core.metrics;

import com.rover.gateway.core.filter.GatewayRequestContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetricsCompletionTest {
    @Test void failedFilterCannotCountPresetSuccessBeforeActualFallbackResponse() {
        check(false);
    }
    @Test void unfinishedFilterCannotCountPresetSuccessBeforeActualFallbackResponse() {
        check(true);
    }
    private void check(boolean normalReturn) {
        var holder = new ChannelHandlerContext[1];
        var channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext ctx) { holder[0] = ctx; }
        });
        var registry = new MetricsRegistry(new MetricsSettings());
        var context = new GatewayRequestContext(holder[0], new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/p"), "/p");
        try {
            context.setStatusCode(200);
            var future = new MetricsFilter(registry).doFilter(context, ignored -> normalReturn
                    ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(new IllegalStateException("broken filter")));
            assertEquals(!normalReturn, future.isCompletedExceptionally());
            assertEquals(0, registry.totalRequests.sum(), "终态未确定之前不能记账");
            context.writeText(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Gateway internal error");
            registry.recordCompleted(context); registry.recordCompleted(context);
            var result = registry.finalResponses().snapshot("__unmatched__", 60, System.currentTimeMillis());
            assertEquals(1L, result.get("finalRequests")); assertEquals(1L, result.get("gateway5xx"));
            FullHttpResponse response = channel.readOutbound();
            assertEquals(500, response.status().code()); response.release();
        } finally { context.getBodyPipe().abort(); channel.finishAndReleaseAll(); }
    }
}
