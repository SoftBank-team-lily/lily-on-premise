package com.lily.onpremise.burst;

import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTunnelTest {

    @TempDir
    Path work;

    private final AtomicInteger starts = new AtomicInteger();
    private volatile ServerSocket listening;
    private final int port = freePort();

    /** ssh 대신 로컬 포트만 연다 */
    private final Commands commands = new Commands() {
        @Override
        public void run(List<String> command, Path workDir) {
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
            starts.incrementAndGet();
            try {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                listening = socket;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
        }
    };

    @AfterEach
    void stop() throws IOException {
        if (listening != null) {
            listening.close();
        }
    }

    @Test
    void 터널이_끊기면_다시_연다() throws Exception {
        DatabaseTunnel tunnel = new DatabaseTunnel(new AgentProperties.Database(
                "bastion", "lily-tunnel", "/keys/id", "rds", 5432, "127.0.0.1", port), commands, work, 100);
        tunnel.ensure();
        assertThat(starts).hasValue(1);

        listening.close();

        long deadline = System.currentTimeMillis() + 5_000;
        while (starts.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(starts).hasValue(2);
        assertThat(listening.isBound() && !listening.isClosed()).isTrue();
    }

    @Test
    void 다시_붙인_앱이_터널을_쓰면_연다() throws Exception {
        DatabaseTunnel tunnel = new DatabaseTunnel(new AgentProperties.Database(
                "bastion", "lily-tunnel", "/keys/id", "rds", 5432, "127.0.0.1", port), commands, work, 100);

        tunnel.resumeFor(Map.of("PATH", "/bin"));
        Thread.sleep(200);
        assertThat(starts).hasValue(0);

        tunnel.resumeFor(Map.of("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/p_x"));
        long deadline = System.currentTimeMillis() + 5_000;
        while (starts.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(starts).hasValue(1);
    }

    @Test
    void 열린_적_없는_터널은_감시하지_않는다() throws Exception {
        new DatabaseTunnel(new AgentProperties.Database(
                "bastion", "lily-tunnel", "/keys/id", "rds", 5432, "127.0.0.1", port), commands, work, 100);

        Thread.sleep(400);

        assertThat(starts).hasValue(0);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
