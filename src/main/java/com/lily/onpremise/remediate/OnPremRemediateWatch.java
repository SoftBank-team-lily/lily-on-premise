package com.lily.onpremise.remediate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.expose.TrafficWindow;
import com.lily.onpremise.platform.PlatformChannel;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.system.Commands;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 30초마다 로컬 5xx 를 본다. 거점이 온프레미스이고 CRITICAL 이 두 번 연속일 때만 슬롯 로그를 읽는다.
 * 스위치가 꺼져 있으면 횟수만 세고, 로그를 읽거나 소켓으로 보내지 않는다.
 */
@Component
public class OnPremRemediateWatch {

    private static final Logger log = LoggerFactory.getLogger(OnPremRemediateWatch.class);
    private static final int CONSECUTIVE = 2;

    private final boolean enabled;
    private final BooleanSupplier onPrem;
    private final Supplier<String> app;
    private final Supplier<TrafficWindow.Sample> traffic;
    private final Supplier<Optional<String>> container;
    private final Function<String, List<String>> logs;
    private final Consumer<LocalIncident.Incident> sender;
    private final CriticalStreaks streaks = new CriticalStreaks();
    private volatile boolean stopped;

    @Autowired
    public OnPremRemediateWatch(AgentRemediateProperties properties, HomeCutover home, LocalExposure exposure,
                                SlotBook slots, Commands commands, PlatformChannel channel) {
        this(properties.enabled(),
                () -> "ONPREM".equals(home.status().phase()),
                () -> {
                    String name = home.status().appName();
                    return name == null ? "" : name;
                },
                () -> exposure.proxy().localTraffic(),
                slots::liveContainer,
                name -> tail(commands, name),
                incident -> channel.send(body(new ObjectMapper(), incident)));
    }

    OnPremRemediateWatch(boolean enabled, BooleanSupplier onPrem, Supplier<String> app,
                         Supplier<TrafficWindow.Sample> traffic, Supplier<Optional<String>> container,
                         Function<String, List<String>> logs, Consumer<LocalIncident.Incident> sender) {
        this.enabled = enabled;
        this.onPrem = onPrem;
        this.app = app;
        this.traffic = traffic;
        this.container = container;
        this.logs = logs;
        this.sender = sender;
    }

    @PostConstruct
    void start() {
        Thread.ofVirtual().name("lily-remediate").start(() -> {
            while (!stopped) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopped) {
                    return;
                }
                try {
                    tick();
                } catch (RuntimeException e) {
                    log.warn("remediate tick failed: {}", e.getMessage());
                }
            }
        });
    }

    @PreDestroy
    void stop() {
        stopped = true;
    }

    void tick() {
        String name = app.get();
        if (name == null || name.isBlank()) {
            return;
        }
        boolean critical = onPrem.getAsBoolean() && traffic.get().critical();
        if (!streaks.ready(name, critical, CONSECUTIVE) || !enabled) {
            return;
        }
        Optional<String> live = container.get();
        if (live.isEmpty() || !live.get().startsWith(name + "-")) {
            return;
        }
        try {
            Optional<LocalIncident.Incident> incident = LocalIncident.from(name, logs.apply(live.get()));
            if (incident.isEmpty()) {
                streaks.ack(name);
                return;
            }
            sender.accept(incident.get());
            streaks.ack(name);
        } catch (RuntimeException e) {
            log.warn("remediate send failed: app={} message={}", name, e.getMessage());
        }
    }

    static List<String> tail(Commands commands, String container) {
        String text = commands.output(List.of("docker", "logs", "--tail", "200", "--since", "15m", container));
        return text.lines().toList();
    }

    private static String body(ObjectMapper json, LocalIncident.Incident incident) {
        ObjectNode node = json.createObjectNode();
        node.put("type", "remediate");
        node.put("app", incident.app());
        node.put("signature", incident.signature());
        node.put("log", incident.log());
        ArrayNode files = node.putArray("files");
        incident.files().forEach(files::add);
        try {
            return json.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("사건을 만들지 못했습니다");
        }
    }
}
