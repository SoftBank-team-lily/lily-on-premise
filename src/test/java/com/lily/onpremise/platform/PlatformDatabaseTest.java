package com.lily.onpremise.platform;

import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** GCP 배포의 cloud-target 이 DB 터널 대상을 바꾸면 열린 터널을 닫고 같은 로컬 포트로 새 대상에 다시 연다 */
class PlatformDatabaseTest {

    @TempDir
    Path work;

    private final int port = freePort();
    /** ssh -L 의 포워딩 대상 (host:port:remote:remotePort) */
    private final List<String> forwards = new CopyOnWriteArrayList<>();
    private final List<String> stopped = new CopyOnWriteArrayList<>();
    private volatile ServerSocket listening;

    /** ssh 대신 로컬 포트만 연다. 끄면 포트를 닫는다 */
    private final Commands commands = new Commands() {
        @Override
        public void run(List<String> command, Path workDir) {
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
            throw new UnsupportedOperationException("startStoppable 을 쓴다");
        }

        @Override
        public Runnable startStoppable(List<String> command, Consumer<String> lines) {
            String forward = command.get(command.indexOf("-L") + 1);
            forwards.add(forward);
            try {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                listening = socket;
                return () -> {
                    stopped.add(forward);
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // 테스트 소켓
                    }
                };
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
        }
    };

    private PlatformDatabase database;

    @BeforeEach
    void open() {
        database = new PlatformDatabase(commands, new AgentProperties.Database(
                "", "lily-tunnel", "", "", 5432, "127.0.0.1", port), work);
    }

    @AfterEach
    void close() throws IOException {
        if (listening != null) {
            listening.close();
        }
    }

    @Test
    void 열린_RDS_터널은_GCP_대상으로_바뀌면_닫고_Cloud_SQL_로_다시_연다() throws Exception {
        database.configure("lily-server", "lily-tunnel", "rds.example", 5432, "cert");
        database.resume(Map.of("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/p_blog"));
        await(() -> forwards.size() == 1);
        assertThat(forwards.get(0)).endsWith(":rds.example:5432");

        database.configure("34.64.37.122", "lily-tunnel", "10.20.0.3", 5432, "cert");

        await(() -> forwards.size() == 2);
        assertThat(stopped).containsExactly(forwards.get(0));
        assertThat(forwards.get(1)).isEqualTo("127.0.0.1:" + port + ":10.20.0.3:5432");
    }

    @Test
    void 열린_적_없는_터널은_대상만_바꾸고_열지_않는다() throws Exception {
        database.configure("lily-server", "lily-tunnel", "rds.example", 5432, "cert");

        database.configure("34.64.37.122", "lily-tunnel", "10.20.0.3", 5432, "cert");
        Thread.sleep(300);

        assertThat(forwards).isEmpty();
        assertThat(database.ready()).isTrue();
    }

    @Test
    void 같은_대상을_다시_받으면_열린_터널을_그대로_둔다() throws Exception {
        database.configure("lily-server", "lily-tunnel", "rds.example", 5432, "cert");
        database.resume(Map.of("DB_URL", "jdbc:postgresql://127.0.0.1:" + port + "/p_blog"));
        await(() -> forwards.size() == 1);

        database.configure("lily-server", "lily-tunnel", "rds.example", 5432, "cert2");
        Thread.sleep(300);

        assertThat(forwards).hasSize(1);
        assertThat(stopped).isEmpty();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
