package com.lily.onpremise.backup;

import com.lily.onpremise.database.DatabaseTransfer;
import com.lily.onpremise.database.LocalDatabase;
import com.lily.onpremise.system.Commands;

import java.util.List;

/**
 * 이 PC 의 {@code lily-postgres} 에서 앱 DB 의 통계를 읽는다. 관리자 계정으로 컨테이너 안에서 묻는다.
 * 읽지 못하면 예외다. 부르는 쪽이 "모름"으로 다룬다.
 */
public final class LocalDbStats {

    static final String CONTAINER = "lily-postgres";
    /** 지금 쿼리를 실행 중인 연결. 이 질의 자신은 뺀다 */
    static final String ACTIVE = "select count(*) from pg_stat_activity where datname = current_database() "
            + "and state = 'active' and pid <> pg_backend_pid()";
    /** 앱 테이블에 들어간·바뀐·지운 행의 누적 수. DB 가 다시 시작하면 0 부터 다시 센다 */
    static final String CHANGES = "select coalesce(sum(n_tup_ins + n_tup_upd + n_tup_del), 0) from pg_stat_user_tables";

    private final Commands commands;

    public LocalDbStats(Commands commands) {
        this.commands = commands;
    }

    public int activeConnections(String app) {
        return (int) scalar(app, ACTIVE);
    }

    public long changeCount(String app) {
        return scalar(app, CHANGES);
    }

    private long scalar(String app, String sql) {
        String out = commands.output(List.of("docker", "exec", CONTAINER, "psql", "-U", "postgres",
                "-p", String.valueOf(LocalDatabase.POSTGRES_PORT), "-d", DatabaseTransfer.localName(app),
                "-X", "-At", "-c", sql));
        String[] lines = out.strip().split("\\R");
        try {
            return Long.parseLong(lines[lines.length - 1].strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("DB 통계를 읽지 못했다: " + out.strip());
        }
    }
}
