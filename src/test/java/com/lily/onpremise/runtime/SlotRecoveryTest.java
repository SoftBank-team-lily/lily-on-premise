package com.lily.onpremise.runtime;

import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SlotRecoveryTest {

    private final SlotBook slots = new SlotBook();
    private final Switch traffic = new Switch();
    private final List<Map<String, String>> resumed = new ArrayList<>();

    @Test
    void 재시작하면_떠_있는_슬롯을_다시_붙인다() {
        SlotRecovery recovery = recovery("""
                lily-postgres\t
                burst-demo-blue\t127.0.0.1:18080->8080/tcp
                """, Set.of(18080));

        assertThat(recovery.recover()).contains("burst-demo-blue");
        assertThat(SlotRecovery.appOf("burst-demo-blue")).isEqualTo("burst-demo");
        assertThat(traffic.port).isEqualTo(18080);
        assertThat(slots.active("burst-demo")).contains(Slot.BLUE);
        assertThat(resumed).singleElement()
                .satisfies(env -> assertThat(env).containsEntry("DB_URL", "jdbc:postgresql://172.17.0.1:15432/p_x"));
    }

    @Test
    void 다시_붙인_슬롯의_환경변수로_지금_스키마를_알려_주지만_롤백_기록은_만들지_않는다() {
        recovery("burst-demo-blue\t127.0.0.1:18080->8080/tcp\n", Set.of(18080)).recover();

        assertThat(slots.liveRelease("burst-demo")).hasValueSatisfying(release -> {
            assertThat(release.slot()).isEqualTo("blue");
            assertThat(release.env()).containsEntry("LILY_DB_SCHEMA", "public_02_add_slug");
        });
        assertThat(slots.currentRelease("burst-demo")).isEmpty();
        assertThat(slots.previousRelease("burst-demo")).isEmpty();

        slots.published(new SlotBook.Release("burst-demo", "green", "img:2", 18081, 8080, "/", Map.of()));
        assertThat(slots.liveRelease("burst-demo")).hasValueSatisfying(r -> assertThat(r.slot()).isEqualTo("green"));
        assertThat(slots.previousRelease("burst-demo")).isEmpty();
    }

    @Test
    void 두_슬롯이_다_떠_있으면_요청을_받는_새_쪽을_붙인다() {
        SlotRecovery recovery = recovery("""
                shop-green\t127.0.0.1:18081->3000/tcp
                shop-blue\t127.0.0.1:18080->3000/tcp
                """, Set.of(18080, 18081));

        assertThat(recovery.recover()).contains("shop-green");
        assertThat(traffic.port).isEqualTo(18081);
    }

    @Test
    void 포트가_응답하지_않거나_슬롯_포트가_아니면_붙이지_않는다() {
        SlotRecovery recovery = recovery("""
                shop-green\t127.0.0.1:18081->3000/tcp
                other-blue\t0.0.0.0:9000->80/tcp
                """, Set.of());

        assertThat(recovery.recover()).isEmpty();
        assertThat(traffic.port).isZero();
    }

    @Test
    void 이미_보낼_곳이_있으면_건드리지_않는다() {
        traffic.port = 18081;
        SlotRecovery recovery = recovery("shop-blue\t127.0.0.1:18080->3000/tcp\n", Set.of(18080));

        assertThat(recovery.recover()).isEmpty();
        assertThat(traffic.port).isEqualTo(18081);
    }

    private SlotRecovery recovery(String ps, Set<Integer> open) {
        Commands commands = new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
            }

            @Override
            public String output(List<String> command) {
                return command.get(1).equals("inspect") ? "DB_URL=jdbc:postgresql://172.17.0.1:15432/p_x\nLILY_DB_SCHEMA=public_02_add_slug\nPATH=/bin\n" : ps;
            }

            @Override
            public void start(List<String> command, Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
        return new SlotRecovery(commands, traffic, slots, 18080, 18081, open::contains, resumed::add);
    }

    private static final class Switch implements TrafficSwitch {
        int port;

        @Override
        public void route(int upstreamPort) {
            port = upstreamPort;
        }

        @Override
        public int upstreamPort() {
            return port;
        }

        @Override
        public String publicUrl() {
            return "";
        }
    }
}
