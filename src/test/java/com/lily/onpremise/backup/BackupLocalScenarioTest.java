package com.lily.onpremise.backup;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lily.onpremise.backup.BackupDecision.Action;
import com.lily.onpremise.backup.BackupRecord.Outcome;
import com.lily.onpremise.database.DatabaseCredentials;
import com.lily.onpremise.database.DatabaseTransfer;
import com.lily.onpremise.system.Commands;
import com.lily.onpremise.system.ProcessCommands;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 컨테이너로 돌리는 백업 시나리오. 평소 테스트에서는 건너뛴다 ({@code LILY_BACKUP_E2E=1} 일 때만).
 *
 * <p>판정·덤프·DB 통계·CPU 는 실제 코드와 실제 컨테이너다. 다른 것은 두 가지다.
 * <ul>
 *   <li>요청 수: 에이전트 프록시 대신 이 테스트의 프록시(:3200 → 앱 :3201)가 센다</li>
 *   <li>사본 DB: builder 대신 "RDS 역할" 컨테이너 주소를 준다</li>
 * </ul>
 * 시계는 판정 한 번에 1분씩 간다 (실제로는 몇 초). 몇 시간짜리 시나리오를 몇 분에 본다.
 *
 * <p>준비 (Docker 네트워크 {@code lily-bk-e2e}):
 * <pre>
 * lily-postgres  postgres:16-alpine -c port=25432, DB·계정 memo/mpw   (에이전트의 PC DB 와 같은 이름·포트)
 * lily-bk-rds    postgres:16-alpine, DB memo, 계정 copy/cpw           (클라우드 사본)
 * lily-bk-app    lily-disaster-test 이미지, DATABASE_URL=postgresql://memo:mpw@lily-postgres:25432/memo, -p 3201:3000
 * </pre>
 * {@code LILY_BACKUP_E2E_SEED} 에 seed-profile.mjs 로 만든 파일을 주면 7일치 기록 시나리오도 본다.
 * 결과는 {@code LILY_BACKUP_E2E_REPORT} 파일에 남긴다.
 */
@EnabledIfEnvironmentVariable(named = "LILY_BACKUP_E2E", matches = "1")
class BackupLocalScenarioTest {

    private static final String APP = "memo";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long TICK_MILLIS = 2_000;

    @TempDir
    Path workDir;

    private final Commands commands = scriptsAsFiles(new ProcessCommands());
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> report = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final MutableClock clock = new MutableClock(Instant.now());
    private HttpServer proxy;
    private int minute;

    @AfterEach
    void tearDown() throws Exception {
        if (proxy != null) {
            proxy.stop(0);
        }
        ((Logger) LoggerFactory.getLogger(BackupScheduler.class)).detachAppender(logs);
        String path = System.getenv("LILY_BACKUP_E2E_REPORT");
        if (path != null && !path.isBlank()) {
            Files.write(Path.of(path), report);
        }
        report.forEach(System.out::println);
    }

    @Test
    void 기준에_맞을_때_백업한다() throws Exception {
        startProxy();
        logs.start();
        ((Logger) LoggerFactory.getLogger(BackupScheduler.class)).addAppender(logs);
        // 바쁠 때 마감을 보려고 주기는 1시간 (판정 60번)
        BackupPolicy policy = new BackupPolicy(1, 10, 5, 1.5, 50, 2, KST);
        BackupScheduler scheduler = scheduler(policy, new BackupStore(workDir, JSON));

        section("1. 첫 판정: 백업한 적이 없으면 바로 FORCE");
        for (int i = 1; i <= 3; i++) {
            post("첫 메모 " + i);
        }
        tick(scheduler);
        expect(scheduler, Action.FORCE);
        report("   사본 메모 수 = " + copyCount() + " (원본 " + sourceCount() + ")");
        assertThat(copyCount()).isEqualTo(sourceCount());

        section("2. 변경 없음: 덤프하지 않고 SKIP");
        tick(scheduler);
        expect(scheduler, Action.SKIP);

        section("3. 메모 하나 쓰고 조용히: 최근 10분 요청이 기준 이하가 되면 RUN");
        post("조용할 때 쓴 메모");
        Action action = Action.NONE;
        for (int i = 0; i < 15 && action != Action.RUN; i++) {
            tick(scheduler);
            action = scheduler.snapshot().decision();
        }
        expect(scheduler, Action.RUN);
        report("   사본 메모 수 = " + copyCount() + " (원본 " + sourceCount() + ")");
        assertThat(copyCount()).isEqualTo(sourceCount());

        section("4. 계속 쓰는 부하: 요청 많음으로 WAIT, 마지막 백업 1시간 뒤 FORCE (쓰는 중에도 성공)");
        AtomicBoolean loading = new AtomicBoolean(true);
        Thread load = Thread.ofVirtual().start(() -> {
            while (loading.get()) {
                try {
                    post("부하");
                    Thread.sleep(50);
                } catch (Exception e) {
                    return;
                }
            }
        });
        boolean waited = false;
        action = Action.NONE;
        for (int i = 0; i < 70 && action != Action.FORCE; i++) {
            tick(scheduler);
            action = scheduler.snapshot().decision();
            waited |= scheduler.snapshot().reason().contains("요청 많음");
        }
        loading.set(false);
        load.join();
        expect(scheduler, Action.FORCE);
        assertThat(waited).isTrue();
        BackupRecord forced = new BackupStore(workDir, JSON).load(APP).recent().getLast();
        report("   마지막 기록 = " + forced.outcome() + (forced.forced() ? " (마감)" : "") + ", 사본 메모 수 = " + copyCount()
                + " (원본 지금 " + sourceCount() + ", 덤프 뒤에 쓴 행은 다음 백업에)");
        assertThat(forced.outcome()).isEqualTo(Outcome.OK);

        String seed = System.getenv("LILY_BACKUP_E2E_SEED");
        if (seed != null && !seed.isBlank()) {
            section("5. 7일치 기록(seed-profile.mjs, 매일 03시가 한가함): 단계 2, 후보 창 밖이면 WAIT");
            Path seeded = Files.createTempDirectory("seeded");
            Files.createDirectories(seeded.resolve("backup"));
            Files.copy(Path.of(seed), seeded.resolve("backup").resolve(APP + ".json"));
            clock.now = Instant.now();
            BackupScheduler week = scheduler(BackupPolicy.defaults(), new BackupStore(seeded, JSON));
            post("7일치 시나리오 메모");
            tick(week);
            BackupScheduler.Snapshot s = week.snapshot();
            report("   단계 " + s.plan().stage() + ", 후보 창 " + s.plan().start().toLocalDateTime() + " ~ "
                    + s.plan().end().toLocalTime() + " KST, 판정 " + s.decision() + " (" + s.reason() + ")");
            assertThat(s.plan().stage()).isEqualTo(2);
            assertThat(s.plan().start().getHour()).isEqualTo(3);
        }

        section("에이전트 로그 (backup decision · start · OK/FAILED)");
        logs.list.forEach(e -> report("   " + e.getFormattedMessage()));
    }

    private BackupScheduler scheduler(BackupPolicy policy, BackupStore store) {
        LocalDbStats db = new LocalDbStats(commands);
        DatabaseCredentials rds = new DatabaseCredentials("postgres", "lily-bk-rds", 5432, "memo", "copy", "cpw", "");
        DatabaseTransfer transfer = new DatabaseTransfer(commands);
        return new BackupScheduler(policy, store,
                () -> new BackupScheduler.Deployed(APP, true, null),
                () -> requests.getAndSet(0),
                new PcLoad(this::appCpu, db),
                new ChangeCounter(db),
                app -> transfer.backupToRemote(app, rds, false),
                Runnable::run,
                clock);
    }

    /** 판정 한 번 = 시뮬레이션 1분. 그 사이 실제로 몇 초 동안 들어온 요청을 그 1분의 요청 수로 본다 */
    private void tick(BackupScheduler scheduler) throws InterruptedException {
        Thread.sleep(TICK_MILLIS);
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        minute++;
        scheduler.tick();
    }

    private void expect(BackupScheduler scheduler, Action action) {
        BackupScheduler.Snapshot s = scheduler.snapshot();
        report("   " + minute + "분째 판정 = " + s.decision() + " (" + s.reason() + ")");
        assertThat(s.decision()).isEqualTo(action);
    }

    private void section(String title) {
        report("");
        report(title);
    }

    private void report(String line) {
        report.add(line);
    }

    private void post(String body) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:3200/api/memos"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(java.util.Map.of("body", body))))
                .build(), HttpResponse.BodyHandlers.discarding());
    }

    private void startProxy() throws Exception {
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 3200), 0);
        proxy.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        proxy.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create("http://127.0.0.1:3201" + exchange.getRequestURI()))
                        .method(exchange.getRequestMethod(), body.length == 0
                                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                Optional.ofNullable(exchange.getRequestHeaders().getFirst("content-type"))
                        .ifPresent(type -> forward.header("content-type", type));
                HttpResponse<byte[]> response = http.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
                exchange.sendResponseHeaders(response.statusCode(), response.body().length == 0 ? -1 : response.body().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
            }
        });
        proxy.start();
    }

    /**
     * Windows 의 Java 는 명령줄을 문자열 하나로 만들어서 {@code sh -c <스크립트>} 의 큰따옴표·줄바꿈이 깨진다
     * (sh 가 앞부분만 받고 아무 일 없이 성공한다). 실제 에이전트는 리눅스 컨테이너라 그대로 간다.
     * 이 테스트에서만 같은 스크립트를 파일로 컨테이너에 넣고 {@code sh 파일} 로 돌린다.
     */
    private static Commands scriptsAsFiles(Commands real) {
        return new Commands() {
            @Override
            public void run(List<String> command, Path dir) {
                int dash = command.lastIndexOf("-c");
                if (dash < 2 || !"sh".equals(command.get(dash - 1)) || dash != command.size() - 2) {
                    real.run(command, dir);
                    return;
                }
                String container = command.get(dash - 2);
                try {
                    Path script = Files.createTempFile("lily-e2e", ".sh");
                    Files.writeString(script, command.get(dash + 1));
                    real.run(List.of("docker", "cp", script.toString(), container + ":/tmp/lily-e2e.sh"), null);
                    Files.deleteIfExists(script);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
                List<String> fixed = new ArrayList<>(command.subList(0, dash));
                fixed.add("/tmp/lily-e2e.sh");
                real.run(fixed, dir);
            }

            @Override
            public String output(List<String> command) {
                return real.output(command);
            }

            @Override
            public void start(List<String> command, java.util.function.Consumer<String> lines) {
                real.start(command, lines);
            }

            @Override
            public void close() {
                real.close();
            }
        };
    }

    private Optional<Double> appCpu() {
        try {
            String out = commands.output(List.of("docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}", "lily-bk-app"));
            return Optional.of(Double.parseDouble(out.strip().replace("%", "")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private long copyCount() {
        return count("lily-bk-rds", "copy", "5432");
    }

    private long sourceCount() {
        return count("lily-postgres", "memo", "25432");
    }

    private long count(String container, String user, String port) {
        String out = commands.output(List.of("docker", "exec", container, "psql", "-U", user, "-p", port, "-d", "memo",
                "-X", "-At", "-c", "select count(*) from memo"));
        String[] lines = out.strip().split("\\R");
        return Long.parseLong(lines[lines.length - 1].strip());
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
