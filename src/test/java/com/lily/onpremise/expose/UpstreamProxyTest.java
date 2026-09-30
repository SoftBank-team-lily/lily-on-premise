package com.lily.onpremise.expose;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamProxyTest {

    @Test
    void 전환_이후_연결만_새_포트로_간다() throws Exception {
        UpstreamProxy proxy = new UpstreamProxy(0);
        proxy.start();
        try (ServerSocket first = listen("one"); ServerSocket second = listen("two")) {
            try (Socket client = connect(proxy.port())) {
                client.setSoTimeout(1000);
                assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
            proxy.switchTo(first.getLocalPort());
            assertThat(exchange(proxy.port())).isEqualTo("one\n");
            proxy.switchTo(second.getLocalPort());
            assertThat(exchange(proxy.port())).isEqualTo("two\n");
        } finally {
            proxy.close();
        }
    }

    private static ServerSocket listen(String word) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        Thread.ofPlatform().daemon(true).start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    socket.getInputStream().readNBytes(5);
                    socket.getOutputStream().write((word + "\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        return server;
    }

    private static String exchange(int port) throws Exception {
        try (Socket client = connect(port)) {
            client.setSoTimeout(2000);
            client.getOutputStream().write("ping\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            String line = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
            return line + "\n";
        }
    }

    private static Socket connect(int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 500);
                return socket;
            } catch (IOException e) {
                last = e;
                Thread.sleep(20);
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("connect failed");
    }
}
