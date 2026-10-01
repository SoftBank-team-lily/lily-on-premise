package com.lily.onpremise.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobMessages;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.pipeline.DatabaseAccess;
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
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 온프레미스 머신은 인바운드 포트를 열지 않는다.
 * 에이전트가 컨트롤 플레인으로 한 소켓을 유지하고, 잡과 상태와 결과를 그 소켓으로만 주고받는다.
 */
@Component
public class WebSocketControlSession implements ControlSession {

    private static final Logger log = LoggerFactory.getLogger(WebSocketControlSession.class);

    private final ObjectProvider<JobSink> jobs;
    private final AgentProperties properties;
    private final AgentIdentity identity;
    private final TrafficSwitch traffic;
    private final ObjectProvider<DatabaseAccess> databases;
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
            ObjectProvider<DatabaseAccess> databases) {
        this.jobs = jobs;
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
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            socket.set(session);
            // database: DB 터널이 설정돼 있어 database 가 있는 잡을 받을 수 있는지. 없으면 컨트롤 플레인이 DB 없이 보낸다
            session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of(
                    "type", "hello",
                    "agentId", identity.id(),
                    "publicUrl", traffic.publicUrl() == null ? "" : traffic.publicUrl(),
                    "version", "0.1.0",
                    "database", databases.getObject() != DatabaseAccess.NONE))));
            log.info("control plane connected: agent={}", identity.id());
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            try {
                JobMessages.Inbound inbound = JobMessages.read(message.getPayload());
                if (inbound.homeApp() != null) {
                    log.info("home received: app={} home={}", inbound.homeApp(), inbound.home());
                    jobs.getObject().home(inbound.homeApp(), inbound.home(), inbound.id());
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
            socket.compareAndSet(session, null);
            CountDownLatch latch = closed.get();
            if (latch != null) {
                latch.countDown();
            }
            log.info("control plane closed: {}", status);
        }
    }
}
