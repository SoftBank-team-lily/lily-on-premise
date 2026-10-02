package com.lily.onpremise.runtime;

import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 에이전트가 다시 뜨면 프록시가 보낼 슬롯을 모른다 ({@link SlotBook} 은 메모리에만 있다). 그동안 앱 컨테이너는 살아 있어도
 * 공개 주소가 503 이었다. 기동할 때 슬롯 포트({@code 127.0.0.1:bluePort|greenPort})에 떠 있는 {@code {app}-blue|green} 을 찾아
 * 다시 붙인다. 그 앱이 쓰던 DB 터널도 다시 연다 ({@code resumed}). 롤백 기록은 되살리지 않는다 (다음 배포부터 다시 쌓인다).
 */
public final class SlotRecovery {

    private static final Logger log = LoggerFactory.getLogger(SlotRecovery.class);
    private static final Pattern LOOPBACK_PORT = Pattern.compile("127\\.0\\.0\\.1:(\\d+)->\\d+/tcp");

    private final Commands commands;
    private final TrafficSwitch traffic;
    private final SlotBook slots;
    private final int bluePort;
    private final int greenPort;
    private final IntPredicate accepts;
    private final Consumer<Map<String, String>> resumed;

    public SlotRecovery(Commands commands, TrafficSwitch traffic, SlotBook slots, int bluePort, int greenPort,
                        Consumer<Map<String, String>> resumed) {
        this(commands, traffic, slots, bluePort, greenPort, SlotRecovery::accepts, resumed);
    }

    SlotRecovery(Commands commands, TrafficSwitch traffic, SlotBook slots, int bluePort, int greenPort,
                 IntPredicate accepts, Consumer<Map<String, String>> resumed) {
        this.commands = commands;
        this.traffic = traffic;
        this.slots = slots;
        this.bluePort = bluePort;
        this.greenPort = greenPort;
        this.accepts = accepts;
        this.resumed = resumed;
    }

    /** @return 다시 붙인 컨테이너. 이미 보낼 곳이 있거나 떠 있는 슬롯이 없으면 비어 있다 */
    public Optional<String> recover() {
        if (traffic.upstreamPort() != 0) {
            return Optional.empty();
        }
        String listed;
        try {
            // 최근에 만든 컨테이너가 먼저 나온다. 배포 도중 죽어 두 슬롯이 다 떠 있으면 새 쪽을 먼저 본다
            listed = commands.output(List.of("docker", "ps", "--format", "{{.Names}}\t{{.Ports}}"));
        } catch (RuntimeException e) {
            log.warn("slot recovery skipped: {}", e.getMessage());
            return Optional.empty();
        }
        for (String line : listed.split("\\R")) {
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            Slot slot = name.endsWith("-blue") ? Slot.BLUE : name.endsWith("-green") ? Slot.GREEN : null;
            if (slot == null) {
                continue;
            }
            int port = slot == Slot.BLUE ? bluePort : greenPort;
            if (!publishes(parts[1], port) || !accepts.test(port)) {
                continue;
            }
            String app = name.substring(0, name.length() - slot.name().length() - 1);
            traffic.route(port);
            slots.commit(app, slot);
            log.info("slot restored: {} on 127.0.0.1:{}", name, port);
            try {
                resumed.accept(env(name));
            } catch (RuntimeException e) {
                log.warn("slot {} env not read: {}", name, e.getMessage());
            }
            return Optional.of(name);
        }
        return Optional.empty();
    }

    private Map<String, String> env(String container) {
        String listed = commands.output(List.of("docker", "inspect", "--format",
                "{{range .Config.Env}}{{println .}}{{end}}", container));
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : listed.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                env.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return env;
    }

    private static boolean publishes(String ports, int port) {
        Matcher matcher = LOOPBACK_PORT.matcher(ports);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == port) {
                return true;
            }
        }
        return false;
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
