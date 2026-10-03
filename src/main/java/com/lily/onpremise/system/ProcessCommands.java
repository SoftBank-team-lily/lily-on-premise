package com.lily.onpremise.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public final class ProcessCommands implements Commands {

    private final List<Process> background = new CopyOnWriteArrayList<>();
    /** 스레드가 끝나기를 기다리는 프로세스. 출력 읽기는 interrupt 로 깨지 않아서 취소는 프로세스를 끊는다 */
    private final Map<Thread, Process> foreground = new ConcurrentHashMap<>();

    @Override
    public void run(List<String> command, Path workDir) {
        finish(command, workDir);
    }

    @Override
    public String output(List<String> command) {
        return finish(command, null);
    }

    private String finish(List<String> command, Path workDir) {
        Process process = startProcess(command, workDir);
        foreground.put(Thread.currentThread(), process);
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(display(command) + " → " + code + " " + scrub(output).trim());
            }
            return scrub(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("interrupted");
        } catch (IOException e) {
            throw new IllegalStateException(display(command) + " → " + e.getMessage(), e);
        } finally {
            foreground.remove(Thread.currentThread(), process);
        }
    }

    @Override
    public void interrupt(Thread owner) {
        Process process = foreground.get(owner);
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Override
    public void start(List<String> command, java.util.function.Consumer<String> lines) {
        Process process = startProcess(command, null);
        background.add(process);
        Thread thread = Thread.ofPlatform().daemon(true).name("lily-proc").unstarted(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.accept(scrub(line));
                }
            } catch (IOException ignored) {
                // 프로세스를 끊으면 읽기가 끝난다
            }
        });
        thread.start();
    }

    @Override
    public void close() {
        background.forEach(Process::destroyForcibly);
        background.clear();
    }

    private static Process startProcess(List<String> command, Path workDir) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        try {
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException(display(command) + " → " + e.getMessage(), e);
        }
    }

    /** 로그와 예외에 토큰이 섞인 클론 주소가 남지 않게 사용자 정보를 지운다. */
    static String scrub(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(https://)[^@\\s/]+@", "$1***@");
    }

    private static String display(List<String> command) {
        return command.stream().map(ProcessCommands::scrub).collect(Collectors.joining(" "));
    }
}
