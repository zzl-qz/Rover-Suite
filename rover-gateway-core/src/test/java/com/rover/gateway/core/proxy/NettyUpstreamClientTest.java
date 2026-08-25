package com.rover.gateway.core.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NettyUpstreamClientTest {

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel listenChannel;
    private Channel browserChannel;
    private HttpServer upstream;
    private NettyUpstreamClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (browserChannel != null) {
            browserChannel.close();
        }
        if (listenChannel != null) {
            listenChannel.close();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void forwardsHelloAndStreamsStatus() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/hello", exchange -> {
            byte[] body = "{\"msg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        CopyOnWriteArrayList<Object> written = new CopyOnWriteArrayList<>();
        ChannelHandlerContext browserCtx = openBrowserCtx(written);
        client = new NettyUpstreamClient(2000, 3000);

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/hello");
        request.headers().set(HttpHeaderNames.HOST, "localhost");

        String target = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/hello";
        HttpProxyClient.ProxyResult result = client.forwardAsync(browserCtx, request, target)
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, result.statusCode());
        assertFalse(result.connectFail());
        assertFalse(result.timeout());
        assertEquals(0, client.getInFlightCount());

        waitUntil(() -> written.stream().anyMatch(HttpResponse.class::isInstance), 2000);
        HttpResponse response = written.stream()
                .filter(HttpResponse.class::isInstance)
                .map(HttpResponse.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(HttpResponseStatus.OK, response.status());
    }

    @Test
    void postBodySurvivesCallerRelease() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/echo", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        upstream.start();

        CopyOnWriteArrayList<Object> written = new CopyOnWriteArrayList<>();
        ChannelHandlerContext browserCtx = openBrowserCtx(written);
        client = new NettyUpstreamClient(2000, 3000);

        byte[] payload = "payload-a".getBytes(StandardCharsets.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/echo",
                Unpooled.copiedBuffer(payload));
        request.headers().set(HttpHeaderNames.HOST, "localhost");
        request.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, payload.length);

        String target = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/echo";
        CompletableFuture<HttpProxyClient.ProxyResult> future = client.forwardAsync(browserCtx, request, target);
        // 模拟 SimpleChannelInboundHandler 在 channelRead0 返回后放掉入站请求
        request.release();
        HttpProxyClient.ProxyResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(200, result.statusCode());
        assertEquals("payload-a", seen.get());
        assertEquals(0, request.refCnt());
        assertEquals(0, client.getInFlightCount());
    }

    @Test
    void streamsBodyOfferedAfterForwardStarts() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/echo", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        upstream.start();

        CopyOnWriteArrayList<Object> written = new CopyOnWriteArrayList<>();
        ChannelHandlerContext browserCtx = openBrowserCtx(written);
        client = new NettyUpstreamClient(2000, 3000);

        io.netty.handler.codec.http.HttpRequest headers = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/echo");
        headers.headers().set(HttpHeaderNames.HOST, "localhost");
        headers.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 9);
        InboundBodyPipe pipe = new InboundBodyPipe(1024);
        String target = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/echo";
        CompletableFuture<HttpProxyClient.ProxyResult> future =
                client.forwardAsync(browserCtx, headers, target, pipe);
        pipe.offer(new DefaultLastHttpContent(
                Unpooled.copiedBuffer("payload-b", StandardCharsets.UTF_8)));

        assertEquals(200, future.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals("payload-b", seen.get());
    }

    @Test
    void connectFailReturnsBadGateway() throws Exception {
        CopyOnWriteArrayList<Object> written = new CopyOnWriteArrayList<>();
        ChannelHandlerContext browserCtx = openBrowserCtx(written);
        client = new NettyUpstreamClient(200, 1000);

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/hello");
        HttpProxyClient.ProxyResult result = client.forwardAsync(
                        browserCtx, request, "http://127.0.0.1:1/api/hello")
                .get(3, TimeUnit.SECONDS);

        assertEquals(502, result.statusCode());
        assertTrue(result.connectFail());
        assertEquals(0, client.getInFlightCount());
    }

    private ChannelHandlerContext openBrowserCtx(CopyOnWriteArrayList<Object> written) throws Exception {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup(1);
        CompletableFuture<ChannelHandlerContext> ready = new CompletableFuture<>();

        ServerBootstrap server = new ServerBootstrap();
        server.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                            @Override
                            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                                written.add(msg);
                                ctx.write(msg, promise);
                            }
                        });
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                ready.complete(ctx);
                            }
                        });
                    }
                });
        listenChannel = server.bind("127.0.0.1", 0).sync().channel();
        int port = ((InetSocketAddress) listenChannel.localAddress()).getPort();

        browserChannel = new Bootstrap()
                .group(worker)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .connect("127.0.0.1", port)
                .sync()
                .channel();
        return ready.get(3, TimeUnit.SECONDS);
    }

    private static void waitUntil(Check check, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timeout waiting for proxy write");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
