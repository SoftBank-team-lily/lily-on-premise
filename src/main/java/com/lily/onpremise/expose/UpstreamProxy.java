package com.lily.onpremise.expose;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 루프백에서만 듣는 HTTP/1.1 리버스 프록시. 요청마다 보낼 곳을 정한다.
 *
 * <ul>
 *   <li>upstream 이 0 이면 503 을 돌려주어, 준비되지 않은 슬롯으로 요청이 들어가지 않게 한다</li>
 *   <li>블루/그린 전환은 다음 요청부터 새 포트로 간다. 처리 중인 요청은 끝까지 기존 포트에서 처리한다</li>
 *   <li>클라우드 버스팅: 로컬에서 처리 중인 요청이 {@link #localLimit} 이면, 넘김 대상({@link #overflowTo})이
 *       켜져 있을 때 그 요청을 클라우드 Ingress 로 보낸다. 헤더는 그대로 넘기므로 Host 는 공개 주소다</li>
 *   <li>점검({@link #pause}): 거점 전환으로 DB 를 옮기는 동안 모든 요청에 503 을 돌려준다</li>
 *   <li>클라우드 비율({@link #cloudShare}): 넘김 대상이 켜져 있으면 로컬 여유와 상관없이 요청마다 그 확률로 클라우드에 보낸다</li>
 * </ul>
 *
 * 앞단(cloudflared)은 keep-alive 로 연결 몇 개에 요청을 몰아 보내므로, 연결 단위로 나누면 분배를 정할 수 없다.
 * 그래서 요청 단위로 나눈다. 101 Switching Protocols(WebSocket) 이후는 바이트를 그대로 잇는다.
 */
public final class UpstreamProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpstreamProxy.class);
    private static final InetAddress LOOPBACK = ipv4Loopback();
    /** 앞단의 keep-alive(cloudflared 90초)보다 길게 잡아 앞단이 먼저 닫게 한다 */
    private static final int CLIENT_IDLE_MILLIS = 120_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 3_000;
    /** 이보다 오래 쉰 업스트림 연결은 재사용하지 않는다 (Node 기본 keep-alive 5초보다 짧게) */
    private static final long UPSTREAM_REUSE_NANOS = 3_000_000_000L;
    /** 끊긴 keep-alive 연결로 보냈을 때 다시 보내도 되는 메서드 */
    private static final Set<String> REPLAYABLE = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<Integer> GATEWAY_ERRORS = Set.of(502, 503, 504);

    private final int requestedPort;
    private ServerSocket server;
    private volatile int upstreamPort;
    /** 점검 중이면 요청을 보내지 않고 503 을 돌려준다 */
    private volatile boolean paused;
    private volatile boolean open;
    private volatile int localLimit = Integer.MAX_VALUE;
    private volatile InetSocketAddress overflow;
    /** 넘김 대상이 있을 때 요청마다 클라우드로 보내는 비율 (0~100) */
    private volatile int cloudPercent;
    /** 로컬 / 클라우드에서 처리 중인 요청 수 */
    private final AtomicInteger localActive = new AtomicInteger();
    private final AtomicInteger remoteActive = new AtomicInteger();
    /** 로컬이 한도에 닿은 채로 들어온 요청 수 (누적). 버스팅 판단에 쓴다 */
    private final AtomicLong saturated = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();
    /** 클라우드가 게이트웨이 오류를 돌려주어 로컬이 다시 처리한 요청 수 (누적) */
    private final AtomicLong fallback = new AtomicLong();
    /** 내 PC 가 처리한 요청의 시간 (최근 5분). 화면의 HOME p95 */
    private final LatencyWindow localLatency = new LatencyWindow(5 * 60_000L, 2048);
    /** 로컬 슬롯이 돌려준 최근 1분. 프록시 자체 503 과 클라우드로 넘긴 요청은 넣지 않는다 */
    private final TrafficWindow localTraffic = new TrafficWindow();
    private Thread acceptThread;

    public UpstreamProxy(int requestedPort) {
        this.requestedPort = requestedPort;
    }

    public synchronized void start() {
        if (open) {
            return;
        }
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(LOOPBACK, requestedPort), 200);
        } catch (IOException e) {
            throw new IllegalStateException("proxy 포트를 열지 못했습니다: " + requestedPort, e);
        }
        open = true;
        acceptThread = Thread.ofPlatform().daemon(true).name("lily-proxy").unstarted(this::acceptLoop);
        acceptThread.start();
    }

    public int port() {
        try {
            return server.getLocalPort();
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

    /** 점검을 켜면 다음 요청부터 503 + Retry-After 다. 처리 중인 요청은 끝까지 간다 */
    public void pause(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    /** 로컬 슬롯이 동시에 처리할 요청 수. 넘치면 넘김 대상으로 보낸다 */
    public void localLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("local limit");
        }
        this.localLimit = limit;
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

    /** 넘김 대상이 켜져 있는 동안 요청마다 이 비율로 클라우드에 보낸다. 넘김(한도 초과)은 따로 계속 동작한다 */
    public void cloudShare(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("cloud percent");
        }
        this.cloudPercent = percent;
    }

    public int cloudShare() {
        return cloudPercent;
    }

    /** 최근 5분 동안 내 PC 가 처리한 요청의 p95 (ms). 요청이 없었으면 -1 */
    public long localP95Millis() {
        return localLatency.p95(System.currentTimeMillis());
    }

    /** 로컬 슬롯 응답의 최근 1분. 프록시가 만든 503 과 클라우드 응답은 빠진다 */
    public TrafficWindow.Sample localTraffic() {
        return localTraffic.lastMinute(System.currentTimeMillis());
    }

    public Pressure pressure() {
        return new Pressure(localActive.get(), remoteActive.get(), localLimit, saturated.get(), overflowed.get(),
                fallback.get());
    }

    /**
     * @param localActive     로컬에서 처리 중인 요청
     * @param remoteActive    클라우드에서 처리 중인 요청
     * @param saturatedTotal  로컬이 한도에 닿은 상태로 들어온 요청 누적
     * @param overflowedTotal 클라우드로 넘긴 요청 누적
     * @param fallbackTotal   클라우드가 502/503/504 를 돌려주어 로컬이 다시 처리한 요청 누적
     */
    public record Pressure(int localActive, int remoteActive, int localLimit, long saturatedTotal, long overflowedTotal,
                           long fallbackTotal) {
    }

    private void acceptLoop() {
        while (open) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("lily-proxy-conn").start(() -> serve(client));
            } catch (IOException e) {
                if (open) {
                    log.warn("proxy accept failed: {}", e.getMessage());
                }
            }
        }
    }

    /** 한 클라이언트 연결에서 요청을 차례로 처리한다. 업스트림 연결은 대상별로 하나씩 재사용한다 */
    private void serve(Socket client) {
        Upstreams upstreams = new Upstreams();
        try (client) {
            client.setSoTimeout(CLIENT_IDLE_MILLIS);
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            while (open) {
                HttpHead request = HttpHead.read(in);
                if (request == null) {
                    return;
                }
                int port = upstreamPort;
                if (port == 0) {
                    reply(out, 503, "no upstream");
                    return;
                }
                if (paused) {
                    reply(out, 503, "maintenance");
                    return;
                }
                Slot slot = acquire();
                boolean keep;
                long started = System.nanoTime();
                try {
                    keep = exchange(client, request, in, out, port, slot, upstreams);
                } finally {
                    if (slot.remote == null) {
                        localLatency.record(System.currentTimeMillis(), (System.nanoTime() - started) / 1_000_000);
                    }
                    slot.release();
                }
                if (!keep) {
                    return;
                }
            }
        } catch (IOException e) {
            log.debug("proxy connection closed: {}", e.getMessage());
        } finally {
            upstreams.close();
        }
    }

    /**
     * 요청 하나를 보내고 응답을 돌려준다.
     *
     * @return 같은 클라이언트 연결로 다음 요청을 받을 수 있으면 true
     */
    private boolean exchange(Socket client, HttpHead request, InputStream in, OutputStream out,
                             int port, Slot slot, Upstreams upstreams) throws IOException {
        InetSocketAddress local = new InetSocketAddress(LOOPBACK, port);
        Upstream upstream = slot.remote != null ? upstreams.get(slot.remote) : null;
        if (slot.remote != null && upstream == null) {
            // 클라우드에 붙지 못하면 로컬로 보낸다
            slot.fallBackToLocal();
        }
        if (upstream == null) {
            upstream = upstreams.get(local);
        }
        if (upstream == null) {
            reply(out, 502, "upstream unavailable");
            return false;
        }

        HttpHead response = send(request, in, upstream);
        if (response == null && upstream.reused && !request.hasRequestBody()
                && REPLAYABLE.contains(request.method())) {
            // 업스트림이 keep-alive 연결을 먼저 닫았다. 새 연결로 한 번 더 보낸다
            upstreams.drop(upstream);
            upstream = upstreams.connectFresh(upstream.address);
            response = upstream == null ? null : send(request, in, upstream);
        }
        if (response == null) {
            if (upstream != null) {
                upstreams.drop(upstream);
            }
            reply(out, 502, "upstream closed");
            return false;
        }

        if (slot.remote != null && GATEWAY_ERRORS.contains(response.status())
                && !request.hasRequestBody() && REPLAYABLE.contains(request.method())) {
            // 클라우드 Ingress 가 아직 Pod 로 보내지 못한다 (엔드포인트 반영 지연 등). 이 요청은 로컬이 처리한다
            upstreams.drop(upstream);
            slot.fallBackToLocal();
            fallback.incrementAndGet();
            upstream = upstreams.get(local);
            response = upstream == null ? null : send(request, in, upstream);
            if (response == null) {
                if (upstream != null) {
                    upstreams.drop(upstream);
                }
                reply(out, 502, "upstream unavailable");
                return false;
            }
        }

        // 100 Continue 같은 중간 응답은 그대로 전달하고 최종 응답을 기다린다
        while (response.status() >= 100 && response.status() < 200 && response.status() != 101) {
            response.writeTo(out);
            out.flush();
            response = HttpHead.read(upstream.in);
            if (response == null) {
                upstreams.drop(upstream);
                return false;
            }
        }
        if (slot.remote == null) {
            localTraffic.record(System.currentTimeMillis(), response.status() >= 500);
        }
        response.writeTo(out);

        if (response.status() == 101) {
            out.flush();
            tunnel(client, in, out, upstream);
            upstreams.drop(upstream);
            return false;
        }

        boolean untilClose = copyResponseBody(request, response, upstream.in, out);
        out.flush();
        boolean keep = !untilClose && request.keepAlive(false) && response.keepAlive(true);
        if (keep) {
            upstream.lastUsed = System.nanoTime();
            upstream.reused = true;
        } else {
            upstreams.drop(upstream);
        }
        return keep;
    }

    /** 요청 헤더와 본문을 보내고 응답 헤더를 읽는다. 업스트림이 이미 닫혀 있었으면 null */
    private static HttpHead send(HttpHead request, InputStream in, Upstream upstream) {
        try {
            request.writeTo(upstream.out);
            if (request.chunked()) {
                copyChunked(in, upstream.out, false);
            } else if (request.contentLength() > 0) {
                copyExactly(in, upstream.out, request.contentLength(), false);
            }
            upstream.out.flush();
            return HttpHead.read(upstream.in);
        } catch (IOException e) {
            log.debug("upstream exchange failed: {} {}", upstream.address, e.getMessage());
            return null;
        }
    }

    /** @return 본문 길이를 알 수 없어 업스트림이 닫을 때까지 읽었으면 true */
    private static boolean copyResponseBody(HttpHead request, HttpHead response, InputStream from, OutputStream to)
            throws IOException {
        int status = response.status();
        if ("HEAD".equalsIgnoreCase(request.method()) || status == 204 || status == 304) {
            return false;
        }
        if (response.chunked()) {
            copyChunked(from, to, true);
            return false;
        }
        long length = response.contentLength();
        if (length >= 0) {
            copyExactly(from, to, length, true);
            return false;
        }
        byte[] buffer = new byte[8192];
        int read;
        while ((read = from.read(buffer)) >= 0) {
            to.write(buffer, 0, read);
            to.flush();
        }
        return true;
    }

    /** flush 가 true 면 읽은 만큼 바로 내보낸다 (스트리밍 응답이 모이지 않게) */
    private static void copyExactly(InputStream from, OutputStream to, long length, boolean flush) throws IOException {
        byte[] buffer = new byte[8192];
        long left = length;
        while (left > 0) {
            int read = from.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) {
                throw new EOFException("본문 도중 연결 종료");
            }
            to.write(buffer, 0, read);
            if (flush) {
                to.flush();
            }
            left -= read;
        }
    }

    /** chunked 본문을 크기 줄까지 그대로 옮긴다 */
    private static void copyChunked(InputStream from, OutputStream to, boolean flush) throws IOException {
        while (true) {
            String sizeLine = HttpHead.readLine(from);
            if (sizeLine == null) {
                throw new EOFException("chunk 도중 연결 종료");
            }
            writeLine(to, sizeLine);
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("잘못된 chunk 크기: " + hex);
            }
            if (size == 0) {
                // 트레일러와 마지막 빈 줄
                String trailer;
                do {
                    trailer = HttpHead.readLine(from);
                    if (trailer == null) {
                        throw new EOFException("chunk 트레일러 도중 연결 종료");
                    }
                    writeLine(to, trailer);
                } while (!trailer.isEmpty());
                if (flush) {
                    to.flush();
                }
                return;
            }
            copyExactly(from, to, size, false);
            String end = HttpHead.readLine(from);
            if (end == null) {
                throw new EOFException("chunk 끝 도중 연결 종료");
            }
            writeLine(to, end);
            if (flush) {
                to.flush();
            }
        }
    }

    /** 101 이후: 양방향으로 바이트를 그대로 잇는다. 한쪽이 끝나면 반대쪽 쓰기를 닫는다 */
    private static void tunnel(Socket client, InputStream in, OutputStream out, Upstream upstream) throws IOException {
        client.setSoTimeout(0);
        Thread up = Thread.ofVirtual().name("lily-proxy-ws").start(() -> {
            pump(in, upstream.out);
            shutdownOutput(upstream.socket);
        });
        pump(upstream.in, out);
        shutdownOutput(client);
        try {
            up.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pump(InputStream from, OutputStream to) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = from.read(buffer)) >= 0) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException e) {
            log.debug("tunnel closed: {}", e.getMessage());
        }
    }

    private static void writeLine(OutputStream to, String line) throws IOException {
        to.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void reply(OutputStream out, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + message + "\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + (status == 503 ? "\r\nRetry-After: 30" : "") + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /**
     * 클라우드 비율에 걸리면 클라우드로 보낸다. 아니면 로컬 자리를 원자적으로 잡는다. 자리가 없으면 넘김 대상이 켜져 있을 때 클라우드로,
     * 꺼져 있으면 한도를 넘더라도 로컬로 보낸다.
     */
    private Slot acquire() {
        InetSocketAddress shared = overflow;
        int share = cloudPercent;
        if (shared != null && share > 0 && (share >= 100 || ThreadLocalRandom.current().nextInt(100) < share)) {
            remoteActive.incrementAndGet();
            overflowed.incrementAndGet();
            return new Slot(shared);
        }
        int limit = localLimit;
        while (true) {
            int current = localActive.get();
            if (current >= limit) {
                break;
            }
            if (localActive.compareAndSet(current, current + 1)) {
                return new Slot(null);
            }
        }
        saturated.incrementAndGet();
        InetSocketAddress remote = overflow;
        if (remote != null) {
            remoteActive.incrementAndGet();
            overflowed.incrementAndGet();
            return new Slot(remote);
        }
        localActive.incrementAndGet();
        return new Slot(null);
    }

    /** 요청 하나가 차지한 자리. remote 가 null 이면 로컬 */
    private final class Slot {
        private InetSocketAddress remote;

        private Slot(InetSocketAddress remote) {
            this.remote = remote;
        }

        void fallBackToLocal() {
            remote = null;
            remoteActive.decrementAndGet();
            localActive.incrementAndGet();
        }

        void release() {
            (remote != null ? remoteActive : localActive).decrementAndGet();
        }
    }

    private static final class Upstream {
        final InetSocketAddress address;
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        long lastUsed = System.nanoTime();
        boolean reused;

        Upstream(InetSocketAddress address, Socket socket) throws IOException {
            this.address = address;
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new BufferedOutputStream(socket.getOutputStream());
        }
    }

    /** 클라이언트 연결 하나가 쓰는 업스트림 연결. 로컬 한 개, 클라우드 한 개 정도다 */
    private static final class Upstreams {
        private Upstream local;
        private Upstream remote;

        /** 쉬던 시간이 짧으면 재사용하고, 아니면 새로 붙는다. 붙지 못하면 null */
        Upstream get(InetSocketAddress address) {
            boolean loopback = address.getAddress() != null && address.getAddress().isLoopbackAddress();
            Upstream cached = loopback ? local : remote;
            if (cached != null && cached.address.equals(address)
                    && System.nanoTime() - cached.lastUsed < UPSTREAM_REUSE_NANOS) {
                return cached;
            }
            if (cached != null) {
                drop(cached);
            }
            return connectFresh(address);
        }

        Upstream connectFresh(InetSocketAddress address) {
            Socket socket = new Socket();
            try {
                socket.connect(address, CONNECT_TIMEOUT_MILLIS);
                socket.setTcpNoDelay(true);
                Upstream upstream = new Upstream(address, socket);
                if (address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
                    local = upstream;
                } else {
                    remote = upstream;
                }
                return upstream;
            } catch (IOException e) {
                log.warn("proxy connect failed: {} {}", address, e.getMessage());
                closeQuietly(socket);
                return null;
            }
        }

        void drop(Upstream upstream) {
            closeQuietly(upstream.socket);
            if (local == upstream) {
                local = null;
            }
            if (remote == upstream) {
                remote = null;
            }
        }

        void close() {
            if (local != null) {
                drop(local);
            }
            if (remote != null) {
                drop(remote);
            }
        }
    }

    @Override
    public synchronized void close() {
        open = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 이미 닫힌 소켓
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

    private static void shutdownOutput(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // 이미 닫힌 소켓
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 닫기 실패는 연결이 이미 끝난 것이다
        }
    }
}
