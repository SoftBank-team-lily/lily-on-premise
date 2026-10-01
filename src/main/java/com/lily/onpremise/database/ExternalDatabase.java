package com.lily.onpremise.database;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * 사용자가 준 DB 주소를 그대로 쓴다 (DB 위치 external). 계정과 database 는 만들지 않는다.
 *
 * <p>주소가 {@code localhost} 면 사용자 PC 의 DB 다. 앱 컨테이너에는 {@code host.docker.internal} 로 바꿔 넣는다
 * (Docker Desktop 은 기본으로 풀리고, Linux 는 컨테이너를 띄울 때 host-gateway 로 연결한다).
 * 에이전트(host 네트워크)는 그 이름이 풀리면 그 이름, 아니면 {@code 127.0.0.1} 로 붙는다.
 */
public final class ExternalDatabase {

    static final String DOCKER_HOST = "host.docker.internal";

    /**
     * @param app   앱 컨테이너가 붙는 주소
     * @param agent 에이전트가 붙는 주소 (스키마 적용, 역방향 터널의 대상)
     */
    public record Resolved(DatabaseCredentials app, DatabaseCredentials agent) {
    }

    /** @throws IllegalArgumentException 주소 형식이 틀렸거나 엔진이 다르다. 메시지에 비밀번호를 넣지 않는다 */
    public Resolved resolve(String engine, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DB 주소 형식이 올바르지 않다 (예: postgresql://user:pass@host:5432/db)");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String given = switch (scheme) {
            case "postgres", "postgresql" -> "postgres";
            case "mysql" -> "mysql";
            default -> throw new IllegalArgumentException("DB 주소는 postgresql:// 또는 mysql:// 로 시작해야 한다");
        };
        if (!given.equals(engine)) {
            throw new IllegalArgumentException("DB 주소는 " + given + " 인데 앱이 쓰는 DB 는 " + engine + " 이다");
        }
        String host = uri.getHost();
        String userInfo = uri.getRawUserInfo();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceFirst("^/", "");
        if (host == null || userInfo == null || !userInfo.contains(":") || path.isBlank() || path.contains("/")) {
            throw new IllegalArgumentException("DB 주소에 계정, 비밀번호, 호스트, database 이름이 모두 있어야 한다"
                    + " (예: " + scheme + "://user:pass@host:" + ("mysql".equals(engine) ? 3306 : 5432) + "/db)");
        }
        int colon = userInfo.indexOf(':');
        String user = decode(userInfo.substring(0, colon));
        String password = decode(userInfo.substring(colon + 1));
        int port = uri.getPort() > 0 ? uri.getPort() : ("mysql".equals(engine) ? 3306 : 5432);
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String database = decode(path);

        boolean loopback = loopback(host);
        String appHost = loopback ? DOCKER_HOST : host;
        String agentHost = loopback ? (resolves(DOCKER_HOST) ? DOCKER_HOST : "127.0.0.1") : host;
        DatabaseCredentials app = new DatabaseCredentials(engine, appHost, port, database, user, password, query);
        return new Resolved(app, app.at(agentHost, port));
    }

    /** 배포 전에 에이전트에서 닿는지 본다. 앱이 3분 헬스 체크 끝에 실패하는 것보다 빨리 이유를 준다 */
    public void check(DatabaseCredentials agent) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(agent.host(), agent.port()), 3_000);
        } catch (IOException e) {
            throw new IllegalStateException("DB 주소 " + agent.host() + ":" + agent.port() + " 에 연결할 수 없다");
        }
    }

    private static boolean loopback(String host) {
        String h = host.toLowerCase();
        return h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1");
    }

    private static boolean resolves(String host) {
        try {
            InetAddress.getByName(host);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
