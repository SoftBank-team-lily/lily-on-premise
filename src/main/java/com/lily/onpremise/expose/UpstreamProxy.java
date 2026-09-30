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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 루프백에서만 듣는다. upstream 이 0 이면 연결을 끊어서, 준비되지 않은 슬롯으로 요청이 들어가지 않게 한다.
 * 이미 붙은 연결은 바꾸지 않고, 이후 연결만 새 포트로 간다.
 *
 * 클라우드 버스팅: 로컬 동시 연결이 {@link #localLimit} 에 닿은 상태에서 새 연결이 오면
 * 넘김 대상({@link #overflowTo})이 켜져 있을 때 그 연결을 클라우드 Ingress 로 보낸다.
 * TCP 그대로 넘기므로 Host 헤더는 공개 주소 그대로 가고, 클라우드 Ingress 가 같은 호스트로 받는다.
 */
public final class UpstreamProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxy.class);
    private static final InetAddress LOOPBACK = ipv4Loopback();

    private final int requestedPort;
    private ServerSocketChannel server;
    private volatile int upstreamPort;
    private volatile boolean open;
    private volatile int localLimit = Integer.MAX_VALUE;
    private volatile InetSocketAddress overflow;
    private volatile long remoteIdleNanos = Long.MAX_VALUE;
    private final AtomicInteger localActive = new AtomicInteger();
    private final AtomicInteger remoteActive = new AtomicInteger();
    /** 로컬이 한도에 닿은 채로 들어온 연결 수 (누적). 버스팅 판단에 쓴다 */
    private final AtomicLong saturated = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();
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

    /** 로컬 슬롯이 동시에 받을 연결 수. 넘치면 넘김 대상으로 보낸다 */
    public void localLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("local limit");
        }
        this.localLimit = limit;
    }

    /**
     * 클라우드로 넘긴 연결이 응답을 끝내고 이 시간 동안 조용하면 닫는다.
     * 앞단(cloudflared)은 keep-alive 로 연결을 풀에 붙잡고 재사용하므로, 닫지 않으면 부하가 끝나도
     * 요청이 계속 클라우드로 가고 remoteActive 가 0 이 되지 않아 스케일 다운도 되지 않는다.
     * 닫힌 뒤 앞단이 새로 여는 연결은 로컬에 자리가 있으면 로컬로 간다.
     */
    public void remoteIdle(int seconds) {
        if (seconds < 1) {
            throw new IllegalArgumentException("remote idle");
        }
        this.remoteIdleNanos = seconds * 1_000_000_000L;
    }

    public void overflowTo(InetSocketAddress target) {
        this.overflow = target;
    }

    public void clearOverflow() {
        this.overflow = null;
    }

    public boolean overflowing() {
        return overflow != null;
    }

    public Pressure pressure() {
        return new Pressure(localActive.get(), remoteActive.get(), localLimit, saturated.get(), overflowed.get());
    }

    /**
     * @param saturatedTotal 로컬이 한도에 닿은 상태로 들어온 연결 누적
     * @param overflowedTotal 클라우드로 넘긴 연결 누적
     */
    public record Pressure(int localActive, int remoteActive, int localLimit, long saturatedTotal, long overflowedTotal) {
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
        InetSocketAddress remote = overflow;
        boolean full = localActive.get() >= localLimit;
        if (full) {
            saturated.incrementAndGet();
        }
        SocketChannel upstream = null;
        AtomicInteger counter = localActive;
        if (full && remote != null) {
            upstream = connect(remote);
            if (upstream != null) {
                counter = remoteActive;
                overflowed.incrementAndGet();
            }
        }
        boolean toCloud = counter == remoteActive;
        counter.incrementAndGet();
        try {
            if (upstream == null) {
                // 넘김이 꺼져 있거나 클라우드 연결 실패: 로컬로 보낸다
                upstream = connect(new InetSocketAddress(LOOPBACK, target));
            }
            if (upstream == null) {
                return;
            }
            client.configureBlocking(false);
            try (Selector selector = Selector.open()) {
                client.register(selector, SelectionKey.OP_READ);
                upstream.register(selector, SelectionKey.OP_READ);
                ByteBuffer fromClient = ByteBuffer.allocate(8192);
                ByteBuffer fromUpstream = ByteBuffer.allocate(8192);
                boolean clientDone = false;
                boolean upstreamDone = false;
                // 요청을 보냈고 아직 응답 바이트가 오지 않았으면 닫지 않는다 (느린 응답을 끊지 않게)
                boolean awaitingResponse = false;
                long lastActivity = System.nanoTime();
                while (!clientDone || !upstreamDone) {
                    selector.select(250);
                    if (toCloud && !awaitingResponse
                            && (overflow == null || System.nanoTime() - lastActivity >= remoteIdleNanos)) {
                        // 응답을 끝낸 유휴 keep-alive 연결: 앞단이 다음 요청을 새 연결로 보내게 닫는다
                        break;
                    }
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
                        if (read > 0) {
                            lastActivity = System.nanoTime();
                            awaitingResponse = inbound;
                        }
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            to.write(buffer);
                        }
                        buffer.clear();
                    }
                }
            }
        } catch (IOException e) {
            log.debug("proxy bridge closed: {}", e.getMessage());
        } finally {
            counter.decrementAndGet();
            if (upstream != null) {
                closeQuietly(upstream);
            }
            closeQuietly(client);
        }
    }

    /** 3초 안에 붙지 못하면 null */
    private static SocketChannel connect(InetSocketAddress address) {
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            if (!channel.connect(address)) {
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (!channel.finishConnect()) {
                    if (System.nanoTime() > deadline) {
                        throw new IOException("connect timeout");
                    }
                    Thread.sleep(5);
                }
            }
            return channel;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("proxy connect failed: {} {}", address, e.getMessage());
        }
        if (channel != null) {
            closeQuietly(channel);
        }
        return null;
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
