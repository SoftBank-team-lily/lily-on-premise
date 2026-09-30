package com.lily.onpremise.expose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * 루프백에서만 듣는다. upstream 이 0 이면 연결을 끊어서, 준비되지 않은 슬롯으로 요청이 들어가지 않게 한다.
 * 이미 붙은 연결은 바꾸지 않고, 이후 연결만 새 포트로 간다.
 */
public final class UpstreamProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxy.class);
    private static final InetAddress LOOPBACK = ipv4Loopback();

    private final int requestedPort;
    private ServerSocketChannel server;
    private volatile int upstreamPort;
    private volatile boolean open;
    private Thread acceptThread;

    public UpstreamProxy(int requestedPort) {
        this.requestedPort = requestedPort;
    }

    public synchronized void start() {
        if (open) {
            return;
        }
        try {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(LOOPBACK, requestedPort), 50);
        } catch (IOException e) {
            throw new IllegalStateException("proxy 포트를 열지 못했습니다: " + requestedPort, e);
        }
        open = true;
        acceptThread = Thread.ofPlatform().daemon(true).name("lily-proxy").unstarted(this::acceptLoop);
        acceptThread.start();
    }

    public int port() {
        try {
            return server.socket().getLocalPort();
        } catch (RuntimeException e) {
            throw new IllegalStateException("proxy 가 아직 없습니다", e);
        }
    }

    public void switchTo(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("upstream port");
        }
        this.upstreamPort = port;
    }

    public int upstreamPort() {
        return upstreamPort;
    }

    private void acceptLoop() {
        while (open) {
            try {
                SocketChannel client = server.accept();
                if (client != null) {
                    Thread.ofPlatform().daemon(true).name("lily-proxy-conn").start(() -> bridge(client));
                }
            } catch (IOException e) {
                if (open) {
                    log.warn("proxy accept failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 한 채널을 두 스레드가 동시에 읽고 쓰면, 이 JDK 에서는 응답 쓰기가 요청 읽기에 막힌다.
     * 연결마다 스레드 하나에서만 읽고 쓴다.
     */
    private void bridge(SocketChannel client) {
        int target = upstreamPort;
        if (target == 0) {
            closeQuietly(client);
            return;
        }
        try (SocketChannel upstream = SocketChannel.open()) {
            upstream.configureBlocking(false);
            client.configureBlocking(false);
            if (!upstream.connect(new InetSocketAddress(LOOPBACK, target))) {
                while (!upstream.finishConnect()) {
                    Thread.sleep(5);
                }
            }
            try (Selector selector = Selector.open()) {
                client.register(selector, SelectionKey.OP_READ);
                upstream.register(selector, SelectionKey.OP_READ);
                ByteBuffer fromClient = ByteBuffer.allocate(8192);
                ByteBuffer fromUpstream = ByteBuffer.allocate(8192);
                boolean clientDone = false;
                boolean upstreamDone = false;
                while (!clientDone || !upstreamDone) {
                    selector.select(1_000);
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isReadable()) {
                            continue;
                        }
                        SocketChannel from = (SocketChannel) key.channel();
                        boolean inbound = from == client;
                        SocketChannel to = inbound ? upstream : client;
                        ByteBuffer buffer = inbound ? fromClient : fromUpstream;
                        int read = from.read(buffer);
                        if (read < 0) {
                            to.shutdownOutput();
                            key.cancel();
                            if (inbound) {
                                clientDone = true;
                            } else {
                                upstreamDone = true;
                            }
                            continue;
                        }
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            to.write(buffer);
                        }
                        buffer.clear();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.debug("proxy bridge closed: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    @Override
    public synchronized void close() {
        open = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 이미 닫힌 채널
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // 닫기 실패는 연결이 이미 끝난 것이다
        }
    }
}
