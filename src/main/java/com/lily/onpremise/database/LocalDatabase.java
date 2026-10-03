package com.lily.onpremise.database;

import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 이 PC 에 DB 를 띄운다 (DB 위치 local). 데이터가 이 PC 밖으로 나가지 않는다.
 *
 * <p>엔진마다 컨테이너 하나 ({@code lily-postgres}, {@code lily-mysql}), 앱마다 database 와 계정 하나다
 * (lily-db-provisioner 와 같은 모델). 데이터는 named volume 에 남아서 에이전트나 컨테이너를 다시 띄워도 그대로다.
 *
 * <p>DB 컨테이너는 host 네트워크에서 {@code 127.0.0.1} 과 docker 브리지 게이트웨이({@code 172.17.0.1}) 에만 듣는다.
 * 앱 컨테이너는 게이트웨이로, 에이전트(스키마 적용·역방향 터널)는 같은 주소로 붙는다. LAN 에는 열지 않는다.
 *
 * <p>관리 명령이 실패해도 비밀번호가 들어간 명령줄은 올리지 않는다 (잡 상태 줄은 화면에 보인다).
 */
public final class LocalDatabase {

    public static final int POSTGRES_PORT = 25432;
    public static final int MYSQL_PORT = 23306;
    private static final Logger log = LoggerFactory.getLogger(LocalDatabase.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Commands commands;
    private final Path dir;
    private final String bindHost;

    public LocalDatabase(Commands commands, Path workDir, String bindHost) {
        this.commands = commands;
        this.dir = workDir.resolve("local-db");
        this.bindHost = bindHost;
    }

    /** 엔진 컨테이너를 띄우고 앱의 database 와 계정을 만든다 (있으면 그대로, 비밀번호는 저장한 값으로 맞춘다) */
    public synchronized DatabaseCredentials prepare(String engine, String appName) {
        String name = appName.replace('-', '_');
        if (!name.matches("[a-z][a-z0-9_]{0,30}")) {
            throw new IllegalArgumentException("DB 이름으로 쓸 수 없는 앱 이름: " + appName);
        }
        if ("mysql".equals(engine)) {
            String password = secret("mysql-" + name);
            ensureMysql(secret("mysql-admin"));
            admin(mysql("CREATE DATABASE IF NOT EXISTS `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
                            + " CREATE USER IF NOT EXISTS '" + name + "'@'%' IDENTIFIED BY '" + password + "';"
                            + " ALTER USER '" + name + "'@'%' IDENTIFIED BY '" + password + "';"
                            + " GRANT ALL PRIVILEGES ON `" + name + "`.* TO '" + name + "'@'%';", "mysql"),
                    "mysql: database " + name);
            return new DatabaseCredentials("mysql", bindHost, MYSQL_PORT, name, name, password, "");
        }
        String admin = secret("postgres-admin");
        String password = secret("postgres-" + name);
        ensurePostgres(admin);
        psql("DO $$BEGIN IF EXISTS (SELECT FROM pg_roles WHERE rolname = '" + name + "') THEN"
                + " ALTER ROLE \"" + name + "\" WITH LOGIN PASSWORD '" + password + "';"
                + " ELSE CREATE ROLE \"" + name + "\" LOGIN PASSWORD '" + password + "'; END IF; END$$",
                "postgres: role " + name);
        try {
            psql("CREATE DATABASE \"" + name + "\" OWNER \"" + name + "\"", "postgres: database " + name);
        } catch (AlreadyExists ignored) {
            // 다시 배포
        }
        // 기본값은 PUBLIC 에 CONNECT 가 열려 있다. 다른 앱 계정이 붙지 못하게 막는다
        psql("REVOKE ALL ON DATABASE \"" + name + "\" FROM PUBLIC", "postgres: revoke " + name);
        return new DatabaseCredentials("postgres", bindHost, POSTGRES_PORT, name, name, password, "");
    }

    /**
     * 앱을 지울 때. 이 PC 의 DB 컨테이너에 있는 앱 database 와 계정을 지우고 저장한 비밀번호도 지운다.
     * 엔진 컨테이너가 없으면 그 엔진은 건너뛴다 (컨테이너를 새로 띄우지 않는다)
     *
     * @return 지운 것. 예: {@code postgres lily_blog}
     */
    public synchronized List<String> drop(String appName) {
        String name = appName.replace('-', '_');
        if (!name.matches("[a-z][a-z0-9_]{0,30}")) {
            throw new IllegalArgumentException("DB 이름으로 쓸 수 없는 앱 이름: " + appName);
        }
        List<String> dropped = new java.util.ArrayList<>();
        if (startExisting("lily-postgres")) {
            await(List.of("docker", "exec", "lily-postgres", "pg_isready", "-q", "-h", "127.0.0.1",
                    "-p", String.valueOf(POSTGRES_PORT), "-U", "postgres"), "postgres");
            // 접속 중인 세션이 있어도 지운다 (앱 컨테이너는 이미 내렸다)
            psql("DROP DATABASE IF EXISTS \"" + name + "\" WITH (FORCE)", "postgres: drop database " + name);
            psql("DROP ROLE IF EXISTS \"" + name + "\"", "postgres: drop role " + name);
            dropped.add("postgres " + name);
        }
        if (startExisting("lily-mysql")) {
            await(mysql(null, "mysqladmin"), "mysql");
            admin(mysql("DROP DATABASE IF EXISTS `" + name + "`; DROP USER IF EXISTS '" + name + "'@'%';", "mysql"),
                    "mysql: drop " + name);
            dropped.add("mysql " + name);
        }
        for (String file : List.of("postgres-" + name, "mysql-" + name)) {
            try {
                Files.deleteIfExists(dir.resolve(file));
            } catch (IOException e) {
                log.warn("db secret not removed: {} {}", file, e.getMessage());
            }
        }
        return dropped;
    }

    private void ensurePostgres(String admin) {
        if (!startExisting("lily-postgres")) {
            admin(List.of("docker", "run", "-d", "--name", "lily-postgres",
                    "--restart", "unless-stopped", "--network", "host", "--label", "lily.agent=database",
                    "-v", "lily-postgres-data:/var/lib/postgresql/data",
                    "-e", "POSTGRES_PASSWORD=" + admin,
                    "postgres:16-alpine",
                    "-c", "port=" + POSTGRES_PORT,
                    "-c", "listen_addresses=" + listen()), "postgres: start container");
            log.info("local postgres started on {}:{}", bindHost, POSTGRES_PORT);
        }
        await(List.of("docker", "exec", "lily-postgres", "pg_isready", "-q", "-h", "127.0.0.1",
                "-p", String.valueOf(POSTGRES_PORT), "-U", "postgres"), "postgres");
    }

    private void ensureMysql(String admin) {
        if (!startExisting("lily-mysql")) {
            admin(List.of("docker", "run", "-d", "--name", "lily-mysql",
                    "--restart", "unless-stopped", "--network", "host", "--label", "lily.agent=database",
                    "-v", "lily-mysql-data:/var/lib/mysql",
                    "-e", "MYSQL_ROOT_PASSWORD=" + admin,
                    "mysql:8.4",
                    "--port=" + MYSQL_PORT, "--bind-address=" + listen(), "--mysqlx=OFF"), "mysql: start container");
            log.info("local mysql started on {}:{}", bindHost, MYSQL_PORT);
        }
        await(mysql(null, "mysqladmin"), "mysql");
    }

    /**
     * 관리자 비밀번호는 컨테이너 환경변수(MYSQL_ROOT_PASSWORD)에서 읽는다. 에이전트 컨테이너를 다시 만들면
     * 이 PC 의 작업 폴더가 비어서 처음 만든 비밀번호를 잃는다. SQL 은 셸이 해석하지 않게 환경변수로 넘긴다
     */
    private static List<String> mysql(String sql, String client) {
        String auth = "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" ";
        if ("mysqladmin".equals(client)) {
            return List.of("docker", "exec", "lily-mysql", "sh", "-c",
                    auth + "mysqladmin ping --silent -uroot -h127.0.0.1 -P" + MYSQL_PORT);
        }
        return List.of("docker", "exec", "-e", "LILY_SQL=" + sql, "lily-mysql", "sh", "-c",
                auth + "mysql -uroot -h127.0.0.1 -P" + MYSQL_PORT + " -e \"$LILY_SQL\"");
    }

    private String listen() {
        return "127.0.0.1".equals(bindHost) ? "127.0.0.1" : "127.0.0.1," + bindHost;
    }

    /** 이미 있는 컨테이너면 켠다 (켜져 있으면 그대로). 없으면 false */
    private boolean startExisting(String container) {
        try {
            commands.run(List.of("docker", "start", container), null);
            return true;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("no such container")) {
                return false;
            }
            throw new IllegalStateException("DB 컨테이너 " + container + " 를 켜지 못했다" + reason(e));
        }
    }

    /** 첫 기동은 초기화 때문에 수십 초 걸린다 */
    private void await(List<String> check, String engine) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (true) {
            try {
                commands.run(check, null);
                return;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException(engine + " 가 2분 안에 뜨지 않았다 (docker logs lily-" + engine + ")");
                }
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
    }

    /** 컨테이너 안 소켓으로 붙는다 (local trust). 비밀번호가 필요 없다 */
    private void psql(String sql, String what) {
        try {
            commands.run(List.of("docker", "exec", "lily-postgres", "psql", "-U", "postgres",
                    "-p", String.valueOf(POSTGRES_PORT), "-v", "ON_ERROR_STOP=1", "-q", "-c", sql), null);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                throw new AlreadyExists();
            }
            throw new IllegalStateException(what + " 실패" + reason(e));
        }
    }

    private void admin(List<String> command, String what) {
        try {
            commands.run(command, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException(what + " 실패" + reason(e));
        }
    }

    /** 명령줄을 빼고 DB·docker 가 돌려준 오류만 남긴다 (비밀번호가 명령줄에 있다) */
    static String reason(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int arrow = message.lastIndexOf(" → ");
        String tail = arrow < 0 ? "" : message.substring(arrow + 3).trim();
        tail = tail.replaceAll("(?i)(password|identified by)\\s*'[^']*'", "$1 ***");
        return tail.isBlank() ? "" : ": " + (tail.length() > 300 ? tail.substring(0, 300) : tail);
    }

    /** 이 PC 에만 두는 비밀번호. 한 번 만들면 계속 쓴다 */
    private String secret(String name) {
        Path file = dir.resolve(name);
        try {
            if (Files.isRegularFile(file)) {
                String value = Files.readString(file).trim();
                if (value.matches("[0-9a-f]{32}")) {
                    return value;
                }
            }
            Files.createDirectories(dir);
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            String value = HexFormat.of().formatHex(bytes);
            Files.writeString(file, value);
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("DB 비밀번호를 저장하지 못했다: " + e.getMessage(), e);
        }
    }

    private static final class AlreadyExists extends RuntimeException {
        AlreadyExists() {
            super(null, null, false, false);
        }
    }
}
