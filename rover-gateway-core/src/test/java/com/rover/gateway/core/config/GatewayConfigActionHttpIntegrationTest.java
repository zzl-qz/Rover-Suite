package com.rover.gateway.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.runtime.GatewayRuntime;
import com.rover.gateway.core.server.GatewayHttpServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import java.net.InetSocketAddress;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production Netty HTTP pipeline over a real loopback TCP socket. */
class GatewayConfigActionHttpIntegrationTest {
    private static final String TOKEN = "integration-admin-token";
    private static final String KEY = GatewayConfigActionStore.TRACE_SAMPLE_RATE;
    @TempDir Path directory;

    @Test
    void authenticatedHttpWriteHasDurableReceiptAndCanBeReadBack() throws Exception {
        var configs = new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(directory.resolve("runtime-config.json")));
        configs.seed(KEY, "1");
        var runtime = new GatewayRuntime(0, List.of(), 1000, 3000, new FilterSettings(),
                DiscoverySettings.staticDefaults(), null, configs, TOKEN,
                new RouteOverlayStore(directory.resolve("routes.json")));
        EventLoopGroup boss = new OioEventLoopGroup(1);
        EventLoopGroup workers = new OioEventLoopGroup(16);
        Channel server = null;
        try {
            server = new ServerBootstrap().group(boss, workers).channel(OioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new HttpServerCodec())
                                    .addLast(new GatewayHttpServerHandler(runtime, true));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();

            HttpReply denied = post(port, "tcp-config-1", "1", "0", false);
            assertEquals(401, denied.status());
            assertEquals("1", currentValue(configs));

            HttpReply applied = post(port, "tcp-config-1", "1", "0", true);
            assertEquals(200, applied.status(), applied.body());
            assertTrue(applied.body().contains("\"status\":\"APPLIED\""), applied.body());
            assertEquals("0", currentValue(configs), "the runtime config must change through the TCP request");

            HttpReply replay = post(port, "tcp-config-1", "1", "0", true);
            assertEquals(200, replay.status(), replay.body());
            assertTrue(replay.body().contains("\"replayed\":true"), replay.body());
            assertEquals("0", currentValue(configs), "replay must not apply a second change");

            HttpReply conflict = post(port, "tcp-config-1", "0", "1", true);
            assertEquals(409, conflict.status(), conflict.body());
            assertEquals("0", currentValue(configs), "conflicting operation id must not mutate config");

            HttpReply receipt = get(port, ManageApiPaths.CONFIG_ACTIONS + "/tcp-config-1");
            assertEquals(200, receipt.status(), receipt.body());
            assertTrue(receipt.body().contains("\"expectedValue\":\"1\""), receipt.body());
            assertTrue(receipt.body().contains("\"desiredValue\":\"0\""), receipt.body());

            HttpReply configsReadback = get(port, ManageApiPaths.CONFIGS);
            assertEquals(200, configsReadback.status(), configsReadback.body());
            assertTrue(configsReadback.body().contains("\"key\":\"" + KEY + "\""), configsReadback.body());
            assertTrue(configsReadback.body().contains("\"value\":\"0\""), configsReadback.body());

            HttpReply staleCas = post(port, "tcp-config-2", "1", "0", true);
            assertEquals(200, staleCas.status(), staleCas.body());
            assertTrue(staleCas.body().contains("\"status\":\"PRECONDITION_FAILED\""), staleCas.body());
            assertEquals("0", currentValue(configs));
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully().syncUninterruptibly();
            boss.shutdownGracefully().syncUninterruptibly();
            runtime.close();
        }
    }

    private static HttpReply post(int port, String operationId, String expected, String desired,
            boolean authorized) throws Exception {
        String body = "{\"operationId\":\"" + operationId + "\",\"key\":\"" + KEY
                + "\",\"expectedValue\":\"" + expected + "\",\"desiredValue\":\""
                + desired + "\"}";
        String headers = "Content-Type: application/json\r\n"
                + (authorized ? "X-Rover-Admin-Token: " + TOKEN + "\r\n" : "");
        return request(port, "POST", ManageApiPaths.CONFIG_ACTIONS, headers, body);
    }

    private static HttpReply get(int port, String path) throws Exception {
        return request(port, "GET", path, "X-Rover-Admin-Token: " + TOKEN + "\r\n", "");
    }

    private static HttpReply request(int port, String method, String path, String headers, String body)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String request = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\n" + headers
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(input);
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            int contentLength = -1;
            String line;
            while (!(line = readLine(input)).isEmpty()) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            if (contentLength < 0) throw new IllegalStateException("响应缺少 Content-Length");
            byte[] bytes = input.readNBytes(contentLength);
            if (bytes.length != contentLength) throw new IllegalStateException("HTTP 响应体未读完整");
            return new HttpReply(status, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String readLine(BufferedInputStream input) throws Exception {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        for (int ch; (ch = input.read()) != -1;) {
            if (previous == '\r' && ch == '\n') { line.setLength(line.length() - 1); return line.toString(); }
            line.append((char) ch); previous = ch;
        }
        throw new IllegalStateException("HTTP 响应头意外结束");
    }

    private record HttpReply(int status, String body) { }

    private static String currentValue(GatewayRuntimeConfigManager configs) {
        return configs.listConfigs().stream().filter(item -> KEY.equals(item.getKey()))
                .findFirst().orElseThrow().getValue();
    }
}
