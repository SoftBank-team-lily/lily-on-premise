package com.lily.onpremise.schema.pgroll;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * 에이전트가 붙을 앱 DB 접속 정보. {@code DatabaseAccess.agentEnv} 의 환경변수에서 읽는다.
 *
 * @param url     JDBC URL. 예: {@code jdbc:postgresql://127.0.0.1:25432/blog}
 * @param sslmode pgroll CLI(lib/pq) 의 sslmode. 이 PC 의 DB 컨테이너는 SSL 이 없어 {@code disable}, RDS 는 {@code require}.
 *                null 이면 CLI 설정 기본값
 */
public record DbTarget(String url, String username, String password, String sslmode) {

    /** env 에 함께 넣어 두는 sslmode 키. 앱 컨테이너에는 넣지 않는다 */
    public static final String SSLMODE = "LILY_PGROLL_SSLMODE";

    public DbTarget(String url, String username, String password) {
        this(url, username, password, null);
    }

    public static DbTarget from(Map<String, String> env) {
        String url = first(env, "DB_URL", "SPRING_DATASOURCE_URL");
        String username = first(env, "DB_USERNAME", "SPRING_DATASOURCE_USERNAME");
        String password = first(env, "DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("DB 접속 정보(DB_URL, DB_USERNAME, DB_PASSWORD)가 없다");
        }
        return new DbTarget(url, username, password, first(env, SSLMODE));
    }

    public boolean postgres() {
        return url.startsWith("jdbc:postgresql:");
    }

    /** 실행 jar 의 클래스로더에서 DriverManager 가 드라이버를 못 찾는 경우를 피한다 (runtimeOnly 의존성) */
    Connection connect() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "5");
        try {
            Driver driver = (Driver) Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance();
            Connection conn = driver.connect(url, props);
            if (conn == null) {
                throw new SQLException("postgres 드라이버가 URL 을 받지 않음");
            }
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("postgres 드라이버를 불러오지 못함", e);
        }
    }

    /** 비밀번호는 로그에 남기지 않는다 */
    @Override
    public String toString() {
        return "DbTarget[" + url + ", " + username + "]";
    }

    private static String first(Map<String, String> env, String... keys) {
        if (env == null) {
            return null;
        }
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
