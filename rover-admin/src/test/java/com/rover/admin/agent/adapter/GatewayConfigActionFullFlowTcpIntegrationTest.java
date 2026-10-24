package com.rover.admin.agent.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.rover.admin.agent.adapter.AdminConfigReadAdapter;
import com.rover.agent.core.model.ConfigActionApproval;
import com.rover.agent.core.model.ConfigActionIntent;
import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.port.GatewayConfigActionReceipt;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.runtime.repository.InMemoryGatewayConfigActionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.action.GatewayConfigActionCommandRunner;
import com.rover.agent.runtime.action.GatewayConfigActionService;
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
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs a persisted S5 config action through its async Runner and the real Gateway TCP HTTP endpoint. */
class GatewayConfigActionFullFlowTcpIntegrationTest {
    private static final String TOKEN = "integration-admin-token";
    private static final String KEY = GatewayConfigActionStore.TRACE_SAMPLE_RATE;
    @TempDir Path directory;

    @Test
    void approvedDurableActionExecutesAndPersistsGatewayReceiptOverTcp() throws Exception {
        var configs = new GatewayRuntimeConfigManager(new GatewayRuntimeConfigApplier(),
                new RuntimeConfigOverlayStore(directory.resolve("runtime-config.json")));
        configs.seed(KEY, "1");
        var runtime = new GatewayRuntime(0, List.of(), 1000, 3000, new FilterSettings(),
                DiscoverySettings.staticDefaults(), null, configs, TOKEN,
                new RouteOverlayStore(directory.resolve("routes.json")));
        EventLoopGroup boss = new OioEventLoopGroup(1);
        EventLoopGroup workers = new OioEventLoopGroup(16);
        Channel server = null;
        GatewayConfigActionCommandRunner runner = null;
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
            AdminProperties properties = new AdminProperties();
            properties.setGatewayUrl("http://127.0.0.1:" + port);
            properties.setAdminToken(TOKEN);
            ManageHttpClient http = mock(ManageHttpClient.class);
            when(http.postJson(eq(properties.getGatewayUrl()), eq(ManageApiPaths.CONFIG_ACTIONS), any()))
                    .thenAnswer(call -> mapper.readValue(jsonResponse(request(port, "POST",
                            ManageApiPaths.CONFIG_ACTIONS, mapper.writeValueAsString(call.getArgument(2)), mapper),
                            mapper), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
            when(http.getJson(eq(properties.getGatewayUrl()), anyString()))
                    .thenAnswer(call -> mapper.readTree(jsonResponse(request(port, "GET", call.getArgument(1), "",
                            mapper), mapper)));
            when(http.getList(eq(properties.getGatewayUrl()), eq(ManageApiPaths.CONFIGS)))
                    .thenAnswer(call -> mapper.readValue(jsonResponse(request(port, "GET", call.getArgument(1), "",
                            mapper), mapper), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { }));
            AdminGatewayConfigActionAdapter adapter = new AdminGatewayConfigActionAdapter(
                    new AdminConfigService(http, properties));

            AgentAccess access = mock(AgentAccess.class);
            when(access.permissions(anyString())).thenReturn(java.util.Set.of(
                    AgentAccess.Permission.APPROVE, AgentAccess.Permission.PROPOSE));
            when(access.managed()).thenReturn(true);
            when(access.writeAllowed(anyString())).thenReturn(true);
            when(access.acquireWritePermit(anyString(), eq(false)))
                    .thenReturn(new AgentAccess.WritePermit() { @Override public void close() { } });
            RouteControlPort control = mock(RouteControlPort.class);
            ResourceScope scope = new ResourceScope("org", "team", "prod");
            when(control.resourceScope()).thenReturn(scope);
            when(control.controlTargetId()).thenReturn("gateway-integration");
            when(control.resourceNamespace()).thenReturn("prod");

            var repository = new InMemoryGatewayConfigActionRepository(100);
            long now = System.currentTimeMillis();
            var intent = new ConfigActionIntent(KEY, "1", "0", scope,
                    "gateway-integration", "prod");
            var action = new GatewayConfigAction("action-full-flow", "session-full-flow", null, null,
                    "proposer", intent, intent.intentHash(), "s5-config-v1",
                    List.of(new ConfigActionApproval("approver-a", intent.intentHash(), "s5-config-v1", now),
                            new ConfigActionApproval("approver-b", intent.intentHash(), "s5-config-v1", now)),
                    "operation-full-flow", ConfigActionStatus.QUEUED, null, now, now, now + 60_000, 0);
            repository.save(action);
            runner = new GatewayConfigActionCommandRunner(repository, adapter, access, control, () -> true);
            runner.poll();

            long deadline = System.currentTimeMillis() + 10_000;
            GatewayConfigAction completed;
            do {
                Thread.sleep(25);
                completed = repository.find(action.actionId()).orElseThrow();
            } while (completed.status() == ConfigActionStatus.EXECUTING && System.currentTimeMillis() < deadline);

            assertEquals(ConfigActionStatus.APPLIED, completed.status());
            assertEquals("0", currentValue(configs));
            assertEquals("operation-full-flow", completed.operationId());
            GatewayConfigActionReceipt receipt = adapter.query("operation-full-flow", KEY, "1", "0");
            assertEquals(GatewayConfigActionReceipt.Status.APPLIED, receipt.status());
            assertTrue(!receipt.message().isBlank(), "the durable Gateway receipt should include its result detail");

            var sessions = new InMemoryAgentSessionRepository(10);
            sessions.save(new Session("session-full-flow", "proposer", null, null, SessionStatus.ACTIVE,
                    now, now, List.of(), scope, "prod"));
            var compensation = new GatewayConfigActionService(repository, sessions,
                    new AdminConfigReadAdapter(new AdminConfigService(http, properties)), control, access, true, () -> true);
            GatewayConfigAction compensationDraft = compensation.proposeCompensation(action.actionId(), "proposer");
            assertEquals(action.actionId(), compensationDraft.compensatesActionId());
            assertEquals("1", compensationDraft.intent().desiredValue());
            assertEquals(ConfigActionStatus.PENDING_APPROVAL, compensationDraft.status());
            compensation.approve(compensationDraft.actionId(), compensationDraft.intentHash(), "approver-c");
            GatewayConfigAction compensationQueued = compensation.approve(
                    compensationDraft.actionId(), compensationDraft.intentHash(), "approver-d");
            assertEquals(ConfigActionStatus.QUEUED, compensationQueued.status());
            runner.poll();

            long compensationDeadline = System.currentTimeMillis() + 10_000;
            GatewayConfigAction compensated;
            do {
                Thread.sleep(25);
                compensated = repository.find(compensationDraft.actionId()).orElseThrow();
            } while (compensated.status() == ConfigActionStatus.EXECUTING
                    && System.currentTimeMillis() < compensationDeadline);

            assertEquals(ConfigActionStatus.APPLIED, compensated.status());
            assertEquals(ConfigActionStatus.APPLIED, repository.find(action.actionId()).orElseThrow().status(),
                    "compensation must preserve the original action and receipt");
            assertEquals("operation-full-flow", repository.find(action.actionId()).orElseThrow().operationId());
            assertEquals("1", currentValue(configs));
            GatewayConfigActionReceipt compensationReceipt = adapter.query(compensated.operationId(), KEY,
                    compensated.intent().beforeValue(), compensated.intent().desiredValue());
            assertEquals(GatewayConfigActionReceipt.Status.APPLIED, compensationReceipt.status());
        } finally {
            if (runner != null) runner.close();
            if (server != null) server.close().syncUninterruptibly();
            workers.shutdownGracefully().syncUninterruptibly();
            boss.shutdownGracefully().syncUninterruptibly();
            runtime.close();
        }
    }

    private static String jsonResponse(HttpReply reply, ObjectMapper mapper) throws Exception {
        if (reply.status() >= 400) {
            String message = mapper.readTree(reply.body()).path("message").asText(reply.body());
            throw new ManageApiCallException(reply.status(), message);
        }
        return reply.body();
    }

    private static HttpReply request(int port, String method, String path, String body, ObjectMapper mapper)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String request = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\nContent-Type: application/json\r\n"
                    + "X-Rover-Admin-Token: " + TOKEN + "\r\nContent-Length: "
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
