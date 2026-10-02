package com.lily.onpremise.database;

import com.lily.onpremise.system.Commands;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 거점 전환 때 앱 DB 를 이 PC 의 DB 컨테이너와 클라우드 RDS 사이에서 옮긴다. 지금은 postgres 만 다룬다.
 *
 * <p>덤프와 복원은 이 PC 의 {@code lily-postgres} 컨테이너 안에서 한다 (host 네트워크라 RDS 터널 주소에 닿는다).
 * 원본은 읽기만 하고, 원본에 닿는 것을 확인한 뒤 대상의 public 스키마를 비우고 원본의 스키마와 데이터로 채운다. 마이그레이션 이력 테이블도
 * 같이 옮겨지므로 대상 쪽 앱이 마이그레이션을 다시 적용하지 않는다.
 *
 * <ul>
 *   <li>{@link #toRemote}: PC → RDS. RDS 쪽은 지난번 이전 때의 사본이라 백업하지 않는다</li>
 *   <li>{@link #toLocal}: RDS → PC. 덮어쓰기 전에 PC DB 를 {@code {db}_bak_{시각}} 으로 복제해 둔다 (마지막 하나만)</li>
 * </ul>
 *
 * 비밀번호는 명령줄 대신 환경변수로 넘기고, 실패 메시지에는 DB 가 돌려준 오류만 남긴다.
 */
public final class DatabaseTransfer {

    static final String CONTAINER = "lily-postgres";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** 셸 함수: local 은 컨테이너 안 소켓(관리자), remote 는 RDS 터널 주소(앱 계정) */
    private static final String FUNCTIONS = """
            set -e -o pipefail
            local_admin() { psql -X -q -v ON_ERROR_STOP=1 -U postgres -p "$LOCAL_PORT" "$@"; }
            local_app() { psql -X -q -v ON_ERROR_STOP=1 -U "$LOCAL_DB" -p "$LOCAL_PORT" -d "$LOCAL_DB" "$@"; }
            remote() { PGPASSWORD="$REMOTE_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -h "$REMOTE_HOST" -p "$REMOTE_PORT" -U "$REMOTE_USER" -d "$REMOTE_DB" "$@"; }
            reset_public() { echo 'DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;'; }
            """;

    /** 양쪽의 테이블 목록과 테이블마다 행 수가 같아야 한다 */
    private static final String VERIFY = """
            tables="select quote_ident(schemaname)||'.'||quote_ident(tablename) from pg_tables where schemaname not in ('pg_catalog','information_schema') order by 1"
            a=$(src -At -c "$tables")
            b=$(dst -At -c "$tables")
            if [ "$a" != "$b" ]; then echo "lily-verify: 테이블 목록이 다르다"; exit 3; fi
            for t in $a; do
              x=$(src -At -c "select count(*) from $t")
              y=$(dst -At -c "select count(*) from $t")
              if [ "$x" != "$y" ]; then echo "lily-verify: $t 행 수가 다르다 ($x / $y)"; exit 3; fi
            done
            echo "lily-verify: ok"
            """;

    private final Commands commands;
    private final String container;
    private final int localPort;
    private final Clock clock;

    public DatabaseTransfer(Commands commands) {
        this(commands, CONTAINER, LocalDatabase.POSTGRES_PORT, Clock.systemUTC());
    }

    DatabaseTransfer(Commands commands, String container, int localPort, Clock clock) {
        this.commands = commands;
        this.container = container;
        this.localPort = localPort;
        this.clock = clock;
    }

    /** 이 PC 의 앱 DB 이름 ({@link LocalDatabase#prepare} 와 같은 규칙) */
    public static String localName(String appName) {
        String name = appName.replace('-', '_');
        if (!name.matches("[a-z][a-z0-9_]{0,30}")) {
            throw new IllegalArgumentException("DB 이름으로 쓸 수 없는 앱 이름: " + appName);
        }
        return name;
    }

    /**
     * PC 의 앱 DB 를 RDS 로 옮긴다. RDS 의 public 스키마는 비우고 채운다.
     *
     * @param appName 앱 (PC DB 이름과 계정이 이 이름이다)
     * @param remote  RDS 의 앱 DB. 주소는 이 PC 에서 닿는 터널 주소
     */
    public void toRemote(String appName, DatabaseCredentials remote) {
        requirePostgres(remote);
        String script = FUNCTIONS + """
                remote -c "select 1" > /dev/null
                remote -c "$(reset_public)"
                pg_dump -U postgres -p "$LOCAL_PORT" --no-owner --no-acl "$LOCAL_DB" | remote
                src() { local_admin -d "$LOCAL_DB" "$@"; }
                dst() { remote "$@"; }
                """ + VERIFY;
        run(script, localName(appName), remote, "PC DB 를 RDS 로 옮기지 못했다");
    }

    /**
     * RDS 의 앱 DB 를 PC 로 옮긴다. PC DB 는 먼저 복제해 두고 (이전 백업은 지운다), 앱 계정으로 채운다.
     *
     * @return PC 에 남긴 백업 DB 이름
     */
    public String toLocal(String appName, DatabaseCredentials remote) {
        requirePostgres(remote);
        String db = localName(appName);
        String backup = db + "_bak_" + STAMP.format(clock.instant());
        String script = FUNCTIONS + """
                for old in $(local_admin -At -c "select datname from pg_database where datname like '${LOCAL_DB}\\_bak\\_%'"); do
                  local_admin -c "DROP DATABASE \\"$old\\""
                done
                local_admin -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$LOCAL_DB' and pid <> pg_backend_pid()" > /dev/null
                remote -c "select 1" > /dev/null
                local_admin -c "CREATE DATABASE \\"$BACKUP_DB\\" TEMPLATE \\"$LOCAL_DB\\" OWNER \\"$LOCAL_DB\\""
                local_app -c "$(reset_public)"
                PGPASSWORD="$REMOTE_PASSWORD" pg_dump -h "$REMOTE_HOST" -p "$REMOTE_PORT" -U "$REMOTE_USER" --no-owner --no-acl "$REMOTE_DB" | local_app
                src() { remote "$@"; }
                dst() { local_admin -d "$LOCAL_DB" "$@"; }
                """ + VERIFY;
        run(script, db, remote, "RDS DB 를 PC 로 옮기지 못했다", "BACKUP_DB=" + backup);
        return backup;
    }

    /** {@link #toLocal} 이 실패했을 때 백업으로 PC DB 를 되돌린다 */
    public void restoreLocal(String appName, String backup) {
        String db = localName(appName);
        if (backup == null || !backup.matches(db + "_bak_\\d{14}")) {
            throw new IllegalArgumentException("백업 이름이 아니다: " + backup);
        }
        String script = FUNCTIONS + """
                local_admin -c "select pg_terminate_backend(pid) from pg_stat_activity where datname in ('$LOCAL_DB', '$BACKUP_DB') and pid <> pg_backend_pid()" > /dev/null
                local_admin -c "DROP DATABASE IF EXISTS \\"$LOCAL_DB\\""
                local_admin -c "ALTER DATABASE \\"$BACKUP_DB\\" RENAME TO \\"$LOCAL_DB\\""
                """;
        run(script, db, null, "PC DB 를 백업으로 되돌리지 못했다", "BACKUP_DB=" + backup);
    }

    private void run(String script, String localDb, DatabaseCredentials remote, String what, String... extra) {
        List<String> command = new ArrayList<>(List.of("docker", "exec"));
        env(command, "LOCAL_PORT", String.valueOf(localPort));
        env(command, "LOCAL_DB", localDb);
        if (remote != null) {
            env(command, "REMOTE_HOST", remote.host());
            env(command, "REMOTE_PORT", String.valueOf(remote.port()));
            env(command, "REMOTE_USER", remote.username());
            env(command, "REMOTE_PASSWORD", remote.password());
            env(command, "REMOTE_DB", remote.database());
        }
        for (String pair : extra) {
            command.add("-e");
            command.add(pair);
        }
        command.addAll(List.of(container, "sh", "-c", script));
        try {
            commands.run(command, null);
        } catch (RuntimeException e) {
            throw new IllegalStateException(what + LocalDatabase.reason(e));
        }
    }

    private static void env(List<String> command, String name, String value) {
        command.add("-e");
        command.add(name + "=" + value);
    }

    private static void requirePostgres(DatabaseCredentials remote) {
        if (!"postgres".equals(remote.engine())) {
            throw new IllegalArgumentException("DB 이전은 postgres 만 지원한다: " + remote.engine());
        }
    }

    /** provisioner 가 준 환경변수(DATABASE_URL)에서 접속 정보를 읽는다 */
    public static DatabaseCredentials fromEnv(Map<String, String> env) {
        String url = env == null ? null : env.get("DATABASE_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL 이 없다");
        }
        return new ExternalDatabase().resolve("postgres", url).agent();
    }
}
