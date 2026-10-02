package com.lily.onpremise.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.UpstreamProxy;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobMessages;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.platform.PlatformChannel;
import com.lily.onpremise.platform.PlatformLink;
import jakarta.annotation.PreDestroy;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 온프레미스 머신은 인바운드 포트를 열지 않는다.
 * 에이전트가 컨트롤 플레인으로 한 소켓을 유지하고, 잡과 상태와 결과를 그 소켓으로만 주고받는다.
 *
 * <pre>
 * 컨트롤 플레인 → {"type":"burst","app":"..","enabled":true,"cloudPercent":30}   버스팅 켜기·끄기와 클라우드 비율
 * 에이전트     → {"type":"burst-state", ...}  {@value #STATE_SECONDS}초마다 버스팅·거점 상태
 * </pre>
 */
@Component
public class WebSocketControlSession implements ControlSession {

    private static final Logger log = LoggerFactory.getLogger(WebSocketControlSession.class);
    static final int STATE_SECONDS = 3;

    private final ObjectProvider<JobSink> jobs;
    private final AgentProperties properties;
    private final AgentIdentity identity;
    private final TrafficSwitch traffic;
    private final ObjectProvider<DatabaseAccess> databases;
    private final PlatformChannel channel;
    private final ObjectProvider<PlatformLink> platform;
    private final ObjectProvider<CloudBurst> burst;
    private final ObjectProvider<HomeCutover> cutover;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<WebSocketSession> socket = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> closed = new AtomicReference<>();

    public WebSocketControlSession(
            ObjectProvider<JobSink> jobs,
            AgentProperties properties,
            AgentIdentity identity,
            TrafficSwitch traffic,
            ObjectProvider<DatabaseAccess> databases,
            PlatformChannel channel,
            ObjectProvider<PlatformLink> platform,
            ObjectProvider<CloudBurst> burst,
            ObjectProvider<HomeCutover> cutover) {
        this.jobs = jobs;
        this.burst = burst;
        this.cutover = cutover;
        this.channel = channel;
        this.platform = platform;
        this.properties = properties;
        this.identity = identity;
        this.traffic = traffic;
        this.databases = databases;
    }

    @Override
    public void connectIfConfigured() {
        String url = properties.controlPlaneUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            throw new IllegalStateException("control-plane-url 은 ws:// 또는 wss:// 여야 합니다");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().daemon(true).name("lily-control").start(() -> loop(url));
    }

    @Override
    public void report(JobRecord record) {
        WebSocketSession current = socket.get();
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            current.sendMessage(new TextMessage(mapper.writeValueAsString(status(record))));
        } catch (IOException e) {
            log.warn("status send failed: {}", e.getMessage());
        }
    }

    @Override
    public boolean connected() {
        WebSocketSession current = socket.get();
        return current != null && current.isOpen();
    }

    @PreDestroy
    public void close() {
        stopping.set(true);
        WebSocketSession current = socket.getAndSet(null);
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // 끊긴 소켓은 재연결 루프가 종료되면 된다
            }
        }
        CountDownLatch latch = closed.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    private void loop(String url) {
        while (!stopping.get()) {
            CountDownLatch latch = new CountDownLatch(1);
            closed.set(latch);
            try {
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.setDefaultMaxTextMessageBufferSize(2 * 1024 * 1024);
                StandardWebSocketClient client = new StandardWebSocketClient(container);
                client.execute(new Handler(), url).get(15, TimeUnit.SECONDS);
                latch.await();
            } catch (Exception e) {
                log.warn("control plane disconnected: {}", e.getMessage());
            }
            if (stopping.get()) {
                return;
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 화면이 보는 버스팅·거점 상태. 앱이 없으면 app 이 비어 있다 */
    Map<String, Object> state() {
        CloudBurst.Status b = burst.getObject().status();
        HomeCutover homes = cutover.getObject();
        HomeCutover.Status h = homes.status();
        UpstreamProxy.Pressure p = b.pressure();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "burst-state");
        state.put("agentId", identity.id());
        state.put("app", b.appName() != null ? b.appName() : h.appName() == null ? "" : h.appName());
        state.put("available", b.available());
        state.put("enabled", b.enabled());
        state.put("cloudPercent", b.cloudPercent());
        state.put("phase", b.phase().name());
        state.put("warm", b.warm());
        state.put("localActive", p.localActive());
        state.put("remoteActive", p.remoteActive());
        state.put("localLimit", p.localLimit());
        state.put("overflowedTotal", p.overflowedTotal());
        state.put("fallbackTotal", p.fallbackTotal());
        state.put("event", b.events().isEmpty() ? "" : b.events().get(0));
        state.put("home", h.phase());
        state.put("movable", homes.movable());
        state.put("homeEvent", h.events().isEmpty() ? "" : h.events().get(0));
        state.put("databaseMode", h.databaseMode());
        state.put("databaseMovable", h.databaseMovable());
        return state;
    }

    private void sendState(WebSocketSession session) {
        while (session.isOpen() && !stopping.get()) {
            try {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(state())));
            } catch (IOException | RuntimeException e) {
                log.debug("burst state send failed: {}", e.getMessage());
            }
            try {
                Thread.sleep(STATE_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Map<String, String> status(JobRecord record) {
        return Map.of(
                "type", "status",
                "agentId", identity.id(),
                "id", record.getId(),
                "status", record.getStatus().name(),
                "line", record.lastLine(),
                "url", record.getUrl() == null ? "" : record.getUrl(),
                "activeSlot", record.getActiveSlot() == null ? "" : record.getActiveSlot());
    }

    private final class Handler extends TextWebSocketHandler {

        @Override
        public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
            // 잡 상태 보고와 Cloudflare 중계 요청이 서로 다른 스레드에서 같은 소켓에 쓴다
            WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 2 * 1024 * 1024);
            socket.set(session);
            channel.attach(text -> {
                try {
                    session.sendMessage(new TextMessage(text));
                } catch (IOException e) {
                    throw new IllegalStateException("컨트롤 플레인으로 보내지 못했습니다: " + e.getMessage(), e);
                }
            });
            // database: DB 터널이 설정돼 있어 database 가 있는 잡을 받을 수 있는지. 없으면 컨트롤 플레인이 DB 없이 보낸다
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("type", "hello");
            hello.put("agentId", identity.id());
            hello.put("publicUrl", traffic.publicUrl() == null ? "" : traffic.publicUrl());
            hello.put("version", "0.1.0");
            hello.put("database", databases.getObject().ready());
            hello.putAll(platform.getObject().hello());
            // 이 에이전트가 받는 메시지. 없으면 컨트롤 플레인은 버스팅 설정을 보내지 않는다
            hello.put("features", java.util.List.of("burst", "home"));
            session.sendMessage(new TextMessage(mapper.writeValueAsString(hello)));
            log.info("control plane connected: agent={}", identity.id());
            Thread.ofVirtual().name("lily-burst-state").start(() -> sendState(session));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(message.getPayload());
                String type = node.path("type").asText();
                if ("welcome".equals(type)) {
                    platform.getObject().welcome(node);
                    return;
                }
                if (node.has("rid") && channel.complete(node)) {
                    return;
                }
                if ("burst".equals(type)) {
                    burst.getObject().configure(node.path("app").asText(""), node.path("enabled").asBoolean(false),
                            node.path("cloudPercent").asInt(0));
                    return;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("rejected message: {}", e.getMessage());
                return;
            }
            try {
                JobMessages.Inbound inbound = JobMessages.read(message.getPayload());
                if (inbound.homeApp() != null) {
                    log.info("home received: app={} home={} migrateDatabase={}", inbound.homeApp(), inbound.home(),
                            inbound.migrateDatabase());
                    jobs.getObject().home(inbound.homeApp(), inbound.home(), inbound.id(), inbound.migrateDatabase());
                } else if (inbound.rollbackApp() != null) {
                    log.info("rollback received: app={}", inbound.rollbackApp());
                    jobs.getObject().rollback(inbound.rollbackApp(), inbound.id());
                } else {
                    DeployJob job = inbound.job();
                    log.info("job received: id={} app={}", job.id(), job.appName());
                    jobs.getObject().accept(job);
                }
            } catch (RuntimeException e) {
                log.warn("rejected message: {}", e.getMessage());
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            WebSocketSession current = socket.get();
            if (current instanceof ConcurrentWebSocketSessionDecorator decorated
                    && decorated.getDelegate() == session) {
                socket.compareAndSet(current, null);
            }
            channel.attach(null);
            CountDownLatch latch = closed.get();
            if (latch != null) {
                latch.countDown();
            }
            log.info("control plane closed: {}", status);
        }
    }
}
