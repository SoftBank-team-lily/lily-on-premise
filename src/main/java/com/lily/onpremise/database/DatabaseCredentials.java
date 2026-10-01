package com.lily.onpremise.database;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 앱 하나의 DB 접속 정보. 환경변수 이름은 lily-db-provisioner 와 같다 (클라우드 배포와 같은 규칙).
 *
 * @param engine postgres 또는 mysql
 * @param query  주소 뒤에 붙일 접속 옵션 (예: {@code ?sslmode=require}). 없으면 ""
 */
public record DatabaseCredentials(String engine, String host, int port, String database, String username,
                                  String password, String query) {

    public DatabaseCredentials {
        query = query == null ? "" : query;
    }

    /** 같은 계정으로 다른 주소 (클라우드 Pod 는 역방향 터널 주소로 붙는다) */
    public DatabaseCredentials at(String host, int port) {
        return new DatabaseCredentials(engine, host, port, database, username, password, query);
    }

    /** DB_* 는 lily-blog-sample 규칙, SPRING_DATASOURCE_* 는 일반 Spring Boot 앱, DATABASE_URL 은 그 밖의 앱 */
    public Map<String, String> env() {
        String hostPort = host + ":" + port;
        String scheme = "mysql".equals(engine) ? "mysql" : "postgresql";
        String jdbc = "jdbc:" + scheme + "://" + hostPort + "/" + database + query;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", jdbc);
        env.put("DB_USERNAME", username);
        env.put("DB_PASSWORD", password);
        env.put("SPRING_DATASOURCE_URL", jdbc);
        env.put("SPRING_DATASOURCE_USERNAME", username);
        env.put("SPRING_DATASOURCE_PASSWORD", password);
        env.put("DATABASE_URL", scheme + "://" + encode(username) + ":" + encode(password) + "@" + hostPort
                + "/" + database + query);
        return env;
    }

    @Override
    public String toString() {
        return "DatabaseCredentials[" + engine + " " + host + ":" + port + "/" + database + " user=" + username + "]";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
