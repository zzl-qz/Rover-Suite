package com.rover.gateway.core.proxy;

import com.rover.gateway.core.config.GatewaySystemProperties;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 走两个真实出站实现，验证响应来源来自实际路径，不能用造好的 ProxyResult 自证。 */
class ProxyObservationTest {
    @Test void upstream503And504AreNeverGatewayFailures() throws Exception {
        for (String engine : new String[]{"jdk", "netty"}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                int status = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
                exchange.sendResponseHeaders(status, 1); exchange.getResponseBody().write(1); exchange.close();
            });
            server.start();
            try {
                for (int status : new int[]{503, 504}) {
                    var result = forward(engine, server.getAddress().getPort(), "/" + status, 1000);
                    assertEquals(status, result.statusCode(), engine);
                    assertEquals("UPSTREAM", result.responseOrigin(), engine);
                    assertEquals("NONE", result.failurePhase(), engine);
                    assertFalse(result.connectFail()); assertFalse(result.timeout()); assertTrue(result.responseComplete());
                }
            } finally { server.stop(0); }
        }
    }

    @Test void timeoutBeforeHeadersIsGateway504() throws Exception {
        for (String engine : new String[]{"jdk", "netty"}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try { Thread.sleep(250); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.start();
            try {
                var result = forward(engine, server.getAddress().getPort(), "/", 80);
                assertEquals(504, result.statusCode(), engine); assertEquals("GATEWAY", result.responseOrigin());
                assertEquals("UPSTREAM_TIMEOUT", result.failurePhase()); assertTrue(result.timeout());
                assertFalse(result.connectFail());
            } finally { server.stop(0); }
        }
    }

    @Test void failureAfterHeadersPreservesAlreadySentStatusAndMarksIncomplete() throws Exception {
        for (String engine : new String[]{"jdk", "netty"}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 100); exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush(); exchange.close();
            });
            server.start();
            try {
                var result = forward(engine, server.getAddress().getPort(), "/", 500);
                assertEquals(200, result.statusCode(), engine); assertEquals("UPSTREAM", result.responseOrigin());
                assertFalse(result.responseComplete(), engine + result); assertFalse(result.connectFail(), engine + result);
            } finally { server.stop(0); }
        }
    }

    private static HttpProxyClient.ProxyResult forward(String engine, int port, String path, int timeout) throws Exception {
        String previous = System.getProperty(GatewaySystemProperties.PROXY_OUTBOUND);
        System.setProperty(GatewaySystemProperties.PROXY_OUTBOUND, engine);
        var holder = new ChannelHandlerContext[1];
        var channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override public void handlerAdded(ChannelHandlerContext context) { holder[0] = context; }
        });
        var client = new HttpProxyClient(500, timeout);
        try {
            var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
            return client.forwardAsync(holder[0], request, "http://127.0.0.1:" + port + path,
                    InboundBodyPipe.empty(1024), true).get(5, TimeUnit.SECONDS);
        } finally {
            client.close(); channel.finishAndReleaseAll();
            if (previous == null) { System.clearProperty(GatewaySystemProperties.PROXY_OUTBOUND); }
            else { System.setProperty(GatewaySystemProperties.PROXY_OUTBOUND, previous); }
        }
    }
}
