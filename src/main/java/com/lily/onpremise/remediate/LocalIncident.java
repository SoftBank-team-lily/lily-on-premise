package com.lily.onpremise.remediate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 슬롯 로그에서 예외와 레포 안 파일 하나만 고른다.
 * Java·Kotlin 은 {@code src/main/java}. Node·Python 은 생성 이미지의 {@code /app} 만 떼고,
 * 그 밖 절대 경로와 {@code node_modules}, {@code site-packages} 는 남기지 않는다.
 */
public final class LocalIncident {

    private static final int LOG_LIMIT = 4_000;
    private static final Pattern ASSIGNED = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|authorization)(\\s*[:=]\\s*)(\\S+)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern AWS = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern GITHUB = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{10,}\\b");
    private static final Pattern EXCEPTION = Pattern.compile("([A-Za-z_$][\\w$]*(?:Exception|Error))");
    private static final Pattern JAVA_FRAME = Pattern.compile("at ([\\w.$]+)\\(([^:)]+):(\\d+)\\)");
    private static final Pattern NODE_FRAME = Pattern.compile("at [^\\n(]*\\(([^()]+):(\\d+):(\\d+)\\)");
    private static final Pattern PYTHON_FRAME = Pattern.compile("File \"([^\"]+)\", line (\\d+)");
    private static final List<String> LIBRARIES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.hibernate.",
            "io.netty.", "reactor.", "org.slf4j.", "ch.qos.", "kotlin.");

    private LocalIncident() {
    }

    public static Optional<Incident> from(String app, List<String> lines) {
        if (app == null || app.isBlank() || lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        List<String> masked = new ArrayList<>();
        for (String line : lines) {
            masked.add(mask(line));
        }
        for (int i = masked.size() - 1; i >= 0; i--) {
            Optional<Incident> incident = frame(app, masked, i);
            if (incident.isPresent()) {
                return incident;
            }
        }
        return Optional.empty();
    }

    private static Optional<Incident> frame(String app, List<String> lines, int index) {
        String line = lines.get(index);
        Matcher java = JAVA_FRAME.matcher(line);
        if (java.find()) {
            String method = java.group(1);
            int dot = method.lastIndexOf('.');
            String typeName = dot < 0 ? method : method.substring(0, dot);
            String file = java.group(2);
            if (!library(typeName) && sourceFile(file)) {
                return Optional.of(incident(app, lines, index, javaPath(typeName, file), Integer.parseInt(java.group(3))));
            }
            return Optional.empty();
        }
        Matcher node = NODE_FRAME.matcher(line);
        if (node.find()) {
            String path = repoPath(node.group(1));
            if (path != null) {
                return Optional.of(incident(app, lines, index, path, Integer.parseInt(node.group(2))));
            }
        }
        Matcher python = PYTHON_FRAME.matcher(line);
        if (python.find()) {
            String path = repoPath(python.group(1));
            if (path != null) {
                return Optional.of(incident(app, lines, index, path, Integer.parseInt(python.group(2))));
            }
        }
        return Optional.empty();
    }

    private static Incident incident(String app, List<String> lines, int index, String path, int line) {
        int start = Math.max(0, index - 30);
        for (int j = index; j >= start; j--) {
            if (lines.get(j).contains("Traceback") || EXCEPTION.matcher(lines.get(j)).find()) {
                start = j;
                break;
            }
        }
        int end = Math.min(lines.size(), index + 4);
        String block = String.join("\n", lines.subList(start, end));
        if (block.length() > LOG_LIMIT) {
            block = block.substring(block.length() - LOG_LIMIT);
        }
        Matcher type = EXCEPTION.matcher(block);
        String name = type.find() ? type.group(1) : "Error";
        String file = path.substring(path.lastIndexOf('/') + 1);
        return new Incident(app, name + " " + file + ":" + line, block, List.of(path));
    }

    static String repoPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.replace('\\', '/');
        if (path.contains("..") || path.contains("node_modules/") || path.contains("site-packages/")) {
            return null;
        }
        if (path.startsWith("node:")) {
            return null;
        }
        if (path.startsWith("/app/")) {
            path = path.substring("/app/".length());
        }
        if (path.startsWith("/") || path.isBlank()) {
            return null;
        }
        return path;
    }

    private static boolean library(String typeName) {
        for (String prefix : LIBRARIES) {
            if (typeName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sourceFile(String file) {
        return file.endsWith(".java") || file.endsWith(".kt") || file.endsWith(".kts");
    }

    private static String javaPath(String typeName, String file) {
        int dot = typeName.lastIndexOf('.');
        String pkg = dot < 0 ? "" : typeName.substring(0, dot).replace('.', '/');
        return pkg.isEmpty() ? file : "src/main/java/" + pkg + "/" + file;
    }

    static String mask(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        String masked = ASSIGNED.matcher(line).replaceAll("$1$2***");
        masked = BEARER.matcher(masked).replaceAll("Bearer ***");
        masked = AWS.matcher(masked).replaceAll("***");
        masked = GITHUB.matcher(masked).replaceAll("***");
        return masked;
    }

    public record Incident(String app, String signature, String log, List<String> files) {
    }
}
