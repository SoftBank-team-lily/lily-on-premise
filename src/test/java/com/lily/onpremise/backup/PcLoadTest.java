package com.lily.onpremise.backup;

import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class PcLoadTest {

    private final List<List<String>> runs = new ArrayList<>();

    private Commands answering(Function<String, String> bySql) {
        return new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
            }

            @Override
            public String output(List<String> command) {
                runs.add(command);
                return bySql.apply(command.get(command.size() - 1));
            }

            @Override
            public void start(List<String> command, Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void 컨테이너_안에서_관리자로_앱_DB_에_묻는다() {
        LocalDbStats db = new LocalDbStats(answering(sql -> "3\n"));

        assertThat(db.activeConnections("my-blog")).isEqualTo(3);
        List<String> command = runs.get(0);
        assertThat(command.subList(0, 3)).containsExactly("docker", "exec", "lily-postgres");
        assertThat(command).containsSequence("-d", "my_blog").contains("-U", "postgres", "25432");
        assertThat(command.get(command.size() - 1)).contains("pg_stat_activity", "pg_backend_pid()");
    }

    @Test
    void CPU_와_DB_연결_수를_함께_읽는다() {
        PcLoad load = new PcLoad(() -> Optional.of(12.5), new LocalDbStats(answering(sql -> "1")));

        assertThat(load.read("blog")).isEqualTo(new PcLoad.Reading(12.5, 1));
    }

    @Test
    void 읽지_못한_값은_null_이다() {
        Commands broken = answering(sql -> {
            throw new IllegalStateException("docker exec → 1 container not running");
        });
        PcLoad load = new PcLoad(Optional::empty, new LocalDbStats(broken));

        assertThat(load.read("blog")).isEqualTo(new PcLoad.Reading(null, null));
    }

    @Test
    void 숫자가_아닌_출력은_읽지_못한_것이다() {
        PcLoad load = new PcLoad(() -> Optional.of(1.0), new LocalDbStats(answering(sql -> "psql: error")));

        assertThat(load.read("blog").activeConnections()).isNull();
    }

    @Test
    void 변경_수는_앱_테이블의_넣기_바꾸기_지우기_합이다() {
        ChangeCounter counter = new ChangeCounter(new LocalDbStats(answering(sql -> "WARNING: x\n128\n")));

        assertThat(counter.current("blog")).isEqualTo(128L);
        assertThat(runs.get(0).get(runs.get(0).size() - 1)).contains("n_tup_ins", "n_tup_upd", "n_tup_del");
    }

    @Test
    void 변경_여부는_백업_때_값과_다르거나_모르면_바뀐_것이다() {
        assertThat(ChangeCounter.changed(128L, 128L)).isFalse();
        assertThat(ChangeCounter.changed(128L, 130L)).isTrue();
        // DB 가 다시 시작해 0 부터 다시 셌다
        assertThat(ChangeCounter.changed(128L, 3L)).isTrue();
        assertThat(ChangeCounter.changed(null, 128L)).isTrue();
        assertThat(ChangeCounter.changed(128L, null)).isTrue();
    }
}
