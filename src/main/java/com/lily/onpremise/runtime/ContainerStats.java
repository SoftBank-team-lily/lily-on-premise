package com.lily.onpremise.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 지금 트래픽을 받는 앱 컨테이너의 CPU·메모리 ({@code docker stats}). 화면의 HOME 자원 칸.
 * 읽을 때마다 docker 를 부르지 않는다. 오래됐으면 뒤에서 한 번 새로 읽고 지난 값을 돌려준다.
 */
@Component
public class ContainerStats {

    private static final Logger log = LoggerFactory.getLogger(ContainerStats.class);
    private static final long FRESH_MILLIS = 10_000;

    /**
     * @param cpuPercent    코어 하나 기준 (여러 코어를 쓰면 100 을 넘는다)
     * @param memoryMiB     쓰는 메모리
     * @param memoryPercent 컨테이너 한도(없으면 PC 메모리) 대비
     */
    public record Usage(String container, double cpuPercent, double memoryMiB, double memoryPercent) {
    }

    private final SlotBook slots;
    private final AtomicBoolean reading = new AtomicBoolean();
    private volatile Usage last;
    private volatile long readAt;

    public ContainerStats(SlotBook slots) {
        this.slots = slots;
    }

    public Optional<Usage> usage() {
        Optional<String> live = slots.liveContainer();
        if (live.isEmpty()) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - readAt > FRESH_MILLIS && reading.compareAndSet(false, true)) {
            String container = live.get();
            Thread.ofVirtual().name("lily-container-stats").start(() -> {
                try {
                    last = read(container);
                } finally {
                    readAt = System.currentTimeMillis();
                    reading.set(false);
                }
            });
        }
        Usage value = last;
        return value != null && value.container().equals(live.get()) ? Optional.of(value) : Optional.empty();
    }

    private static Usage read(String container) {
        try {
            Process process = new ProcessBuilder("docker", "stats", "--no-stream", "--format",
                    "{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}", container).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? parse(container, out) : null;
        } catch (IOException e) {
            log.debug("docker stats failed: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** {@code 12.34%|123.4MiB / 7.66GiB|1.57%} */
    static Usage parse(String container, String line) {
        String[] parts = line.split(java.util.regex.Pattern.quote("|"));
        if (parts.length < 3) {
            return null;
        }
        try {
            double cpu = percent(parts[0]);
            double memory = mebibytes(parts[1].split("/")[0].trim());
            return new Usage(container, cpu, memory, percent(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double percent(String value) {
        return Double.parseDouble(value.trim().replace("%", ""));
    }

    static double mebibytes(String value) {
        String text = value.trim();
        int unit = 0;
        while (unit < text.length() && (Character.isDigit(text.charAt(unit)) || text.charAt(unit) == '.')) {
            unit++;
        }
        double number = Double.parseDouble(text.substring(0, unit));
        return switch (text.substring(unit).trim().toLowerCase(Locale.ROOT)) {
            case "b" -> number / (1024 * 1024);
            case "kib", "kb" -> number / 1024;
            case "mib", "mb" -> number;
            case "gib", "gb" -> number * 1024;
            case "tib", "tb" -> number * 1024 * 1024;
            default -> throw new NumberFormatException(text);
        };
    }
}
