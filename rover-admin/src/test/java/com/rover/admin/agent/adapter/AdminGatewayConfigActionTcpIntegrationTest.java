package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.admin.client.ManageApiCallException;
import com.rover.admin.client.ManageHttpClient;
import com.rover.admin.config.AdminProperties;
import com.rover.admin.service.AdminConfigService;
import com.rover.agent.core.port.GatewayConfigActionException;
import com.rover.agent.core.port.GatewayConfigActionReceipt;
import com.rover.common.config.RuntimeConfigOverlayStore;
import com.rover.common.constants.ManageApiPaths;
import com.rover.gateway.core.config.GatewayConfigActionStore;
import com.rover.gateway.core.config.GatewayRuntimeConfigApplier;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
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
import java.net.Socket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production Admin HTTP client, adapter, and Gateway management HTTP pipeline over TCP. */
class AdminGatewayConfigActionTcpIntegrationTest {
    private static final String TOKEN = "integration-admin-token";
    private static final String KEY = GatewayConfigActionStore.TRACE_SAMPLE_RATE;
    @TempDir Path directory;

    @Test
    void authenticatedAdminClientExecutesAndReconcilesAgainstRealGateway() throws Exception {
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

            ObjectMapper mapper = new ObjectMapper();
            var authorized = adapter(port, TOKEN, mapper);
            GatewayConfigActionReceipt applied = authorized.execute("admin-tcp-op", KEY, "1", "0");
            assertEquals(GatewayConfigActionReceipt.Status.APPLIED, applied.status());
            assertEquals("0", currentValue(configs));

            GatewayConfigActionReceipt queried = authorized.query("admin-tcp-op", KEY, "1", "0");
            assertEquals(GatewayConfigActionReceipt.Status.APPLIED, queried.status());
            assertEquals("1", queried.expectedValue());
            assertEquals("0", queried.desiredValue());

            GatewayConfigActionReceipt replay = authorized.execute("admin-tcp-op", KEY, "1", "0");
            assertTrue(replay.replayed());
            assertEquals("0", currentValue(configs), "idempotent replay must not reapply the mutation");

            GatewayConfigActionException conflict = assertThrows(GatewayConfigActionException.class,
                    () -> authorized.execute("admin-tcp-op", KEY, "0", "1"));
            assertFalse(conflict.outcomeUnknown(), "Gateway's HTTP 409 is a definitive rejection");
            assertEquals("0", currentValue(configs));

            GatewayConfigActionException denied = assertThrows(GatewayConfigActionException.class,
                    () -> adapter(port, "wrong-token", mapper).execute("admin-tcp-denied", KEY, "0", "1"));
            assertFalse(denied.outcomeUnknown(), "Gateway's HTTP 401 is a definitive rejection");
            assertEquals("0", currentValue(configs));

            GatewayConfigActionReceipt missing = authorized.query("admin-tcp-missing", KEY, "0", "1");
            assertEquals(GatewayConfigActionReceipt.Status.UNKNOWN, missing.status());
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully().syncUninterruptibly();
            boss.shutdownGracefully().syncUninterruptibly();
            runtime.close();
        }
    }

    private static AdminGatewayConfigActionAdapter adapter(int port, String token, ObjectMapper mapper) throws Exception {
        AdminProperties properties = new AdminProperties();
        properties.setGatewayUrl("http://127.0.0.1:" + port);
        properties.setAdminToken(token);
        ManageHttpClient http = mock(ManageHttpClient.class);
        when(http.postJson(eq(properties.getGatewayUrl()), eq(ManageApiPaths.CONFIG_ACTIONS), any()))
                .thenAnswer(call -> mapper.readValue(jsonResponse(request(port, "POST",
                        ManageApiPaths.CONFIG_ACTIONS, token, mapper.writeValueAsString(call.getArgument(2)), mapper),
                        mapper), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
        when(http.getJson(eq(properties.getGatewayUrl()), anyString()))
                .thenAnswer(call -> mapper.readTree(jsonResponse(request(port, "GET", call.getArgument(1), token,
                        "", mapper), mapper)));
        AdminConfigService service = new AdminConfigService(http, properties);
        return new AdminGatewayConfigActionAdapter(service);
    }

    private static String jsonResponse(HttpReply reply, ObjectMapper mapper) throws Exception {
        if (reply.status() >= 400) {
            String message = mapper.readTree(reply.body()).path("message").asText(reply.body());
            throw new ManageApiCallException(reply.status(), message);
        }
        return reply.body();
    }

    private static HttpReply request(int port, String method, String path, String token, String body,
                                     ObjectMapper mapper) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String request = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\nContent-Type: application/json\r\n"
                    + "X-Rover-Admin-Token: " + token + "\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
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
            if (contentLength < 0) throw new IllegalStateException("Gateway 响应缺少 Content-Length");
            byte[] bytes = input.readNBytes(contentLength);
            if (bytes.length != contentLength) throw new IllegalStateException("Gateway 响应体未读完整");
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
        throw new IllegalStateException("Gateway HTTP 响应头意外结束");
    }

    private static String currentValue(GatewayRuntimeConfigManager configs) {
        return configs.listConfigs().stream().filter(item -> KEY.equals(item.getKey()))
                .findFirst().orElseThrow().getValue();
    }

    private record HttpReply(int status, String body) { }

}
