package com.lily.onpremise.job;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 컨트롤 플레인이 에이전트에 넘기는 배포 한 건.
 * 프로젝트 목록과 GitHub 연동 상태는 여기 없다. 그 일은 컨트롤 플레인이 하고, 에이전트는 이 잡만 실행한다.
 * 토큰은 클론에만 쓰고 실행 기록에는 남기지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeployJob(
        String id,
        String repoUrl,
        String branch,
        String token,
        String appName,
        Integer targetPort,
        String healthPath,
        String rootDir,
        String dockerfile,
        Map<String, String> env,
        String database,
        String canaryPath,
        Map<String, String> migrations,
        /** 컨트롤 플레인이 터널 주소 기준으로 만든 DB 접속 환경변수 (플랫폼 DB 터널일 때) */
        Map<String, String> databaseEnv,
        /** DB 위치. cloud(기본): 클라우드 RDS 를 터널로, local: 이 PC 에 띄운 DB, external: databaseUrl */
        String databaseMode,
        /** external 일 때 DB 주소. postgresql://user:pass@host:port/db 또는 mysql://... */
        String databaseUrl,
        /**
         * local 일 때 클라우드 RDS 의 데이터를 이 PC DB 로 옮긴 뒤 띄운다 (클라우드 앱을 이 PC 로 옮길 때).
         * RDS 접속 정보는 databaseEnv (터널 주소 기준). postgres 만
         */
        Boolean importDatabase) {

    /** RDS 데이터를 옮기지 않는 잡 */
    public DeployJob(String id, String repoUrl, String branch, String token, String appName, Integer targetPort,
                     String healthPath, String rootDir, String dockerfile, Map<String, String> env, String database,
                     String canaryPath, Map<String, String> migrations, Map<String, String> databaseEnv,
                     String databaseMode, String databaseUrl) {
        this(id, repoUrl, branch, token, appName, targetPort, healthPath, rootDir, dockerfile, env, database,
                canaryPath, migrations, databaseEnv, databaseMode, databaseUrl, null);
    }

    /** DB 는 클라우드 RDS (이 필드 전의 잡) */
    public DeployJob(String id, String repoUrl, String branch, String token, String appName, Integer targetPort,
                     String healthPath, String rootDir, String dockerfile, Map<String, String> env, String database,
                     String canaryPath, Map<String, String> migrations, Map<String, String> databaseEnv) {
        this(id, repoUrl, branch, token, appName, targetPort, healthPath, rootDir, dockerfile, env, database,
                canaryPath, migrations, databaseEnv, null, null);
    }

    /** DB 없이 배포 */
    public DeployJob(String id, String repoUrl, String branch, String token, String appName, Integer targetPort,
                     String healthPath, String rootDir, String dockerfile, Map<String, String> env) {
        this(id, repoUrl, branch, token, appName, targetPort, healthPath, rootDir, dockerfile, env, null);
    }

    /** 판정 경로와 마이그레이션 없이 배포 */
    public DeployJob(String id, String repoUrl, String branch, String token, String appName, Integer targetPort,
                     String healthPath, String rootDir, String dockerfile, Map<String, String> env, String database) {
        this(id, repoUrl, branch, token, appName, targetPort, healthPath, rootDir, dockerfile, env, database, null, null);
    }

    /** 컨트롤 플레인이 DB 접속 정보를 싣지 않은 잡 */
    public DeployJob(String id, String repoUrl, String branch, String token, String appName, Integer targetPort,
                     String healthPath, String rootDir, String dockerfile, Map<String, String> env, String database,
                     String canaryPath, Map<String, String> migrations) {
        this(id, repoUrl, branch, token, appName, targetPort, healthPath, rootDir, dockerfile, env, database,
                canaryPath, migrations, null);
    }

    /** DB 위치. 비었으면 cloud */
    public String databaseModeOrDefault() {
        return databaseMode == null || databaseMode.isBlank() ? "cloud" : databaseMode;
    }

    /** 이 PC DB 를 띄우기 전에 클라우드 RDS 의 데이터를 옮긴다 */
    public boolean importsDatabase() {
        return Boolean.TRUE.equals(importDatabase);
    }

    /** DB 비밀번호가 든 주소를 로그에 남기지 않는다 */
    @Override
    public String toString() {
        return "DeployJob[id=" + id + ", appName=" + appName + ", repoUrl=" + repoUrl + ", branch=" + branch
                + ", database=" + database + ", databaseMode=" + databaseMode
                + (importsDatabase() ? ", importDatabase=true" : "") + "]";
    }

    public DeployJob normalize() {
        String repo = require(repoUrl, "repoUrl");
        URI uri = parseRepo(repo);
        String name = require(appName, "appName");
        if (!name.matches("[a-z][a-z0-9-]{0,30}")) {
            throw new IllegalArgumentException("appName 은 소문자로 시작하는 영숫자와 하이픈만 가능합니다");
        }

        String idValue = id == null || id.isBlank() ? UUID.randomUUID().toString().substring(0, 8) : id;
        if (!idValue.matches("[a-z0-9][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("id 는 소문자 영숫자와 하이픈만 가능합니다");
        }

        int port = targetPort == null ? 8080 : targetPort;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("targetPort 가 올바르지 않습니다");
        }

        String branchValue = branch == null || branch.isBlank() ? "main" : branch;
        if (!branchValue.matches("[A-Za-z0-9._/-]{1,80}")) {
            throw new IllegalArgumentException("branch 가 올바르지 않습니다");
        }

        String health = blankToNull(healthPath);
        if (health != null && !"tcp".equalsIgnoreCase(health)
                && (!health.startsWith("/") || health.contains(" ") || health.contains(".."))) {
            throw new IllegalArgumentException("healthPath 는 / 로 시작해야 합니다");
        }
        if ("tcp".equalsIgnoreCase(health)) {
            health = "tcp";
        }

        String canary = blankToNull(canaryPath);
        if (canary != null && (!canary.startsWith("/") || canary.contains(" ") || canary.contains(".."))) {
            throw new IllegalArgumentException("canaryPath 는 / 로 시작해야 합니다");
        }

        String dir = blankToNull(rootDir);
        if (dir != null && (dir.contains("..") || dir.startsWith("/") || dir.startsWith("\\") || dir.contains("\\"))) {
            throw new IllegalArgumentException("rootDir 은 checkout 안의 상대 경로여야 합니다");
        }

        String file = dockerfile;
        if (file != null && file.length() > 100_000) {
            throw new IllegalArgumentException("dockerfile 이 너무 깁니다");
        }

        String engine = blankToNull(database);
        if (engine != null && !engine.matches("postgres|mysql")) {
            throw new IllegalArgumentException("database 는 postgres 또는 mysql 입니다");
        }
        String mode = blankToNull(databaseMode);
        if (mode != null && !mode.matches("cloud|local|external")) {
            throw new IllegalArgumentException("databaseMode 는 cloud, local, external 입니다");
        }
        String url = blankToNull(databaseUrl);
        if ("external".equals(mode)) {
            if (engine == null) {
                throw new IllegalArgumentException("databaseUrl 은 database 와 같이 보냅니다");
            }
            if (url == null || url.length() > 500 || url.chars().anyMatch(c -> c <= ' ')) {
                throw new IllegalArgumentException("databaseUrl 이 올바르지 않습니다");
            }
        } else {
            url = null;
        }
        boolean importing = Boolean.TRUE.equals(importDatabase);
        if (importing) {
            if (!"local".equals(mode) || !"postgres".equals(engine)) {
                throw new IllegalArgumentException("importDatabase 는 databaseMode local, database postgres 일 때만 씁니다");
            }
            if (databaseEnv == null || databaseEnv.isEmpty()) {
                throw new IllegalArgumentException("importDatabase 는 RDS 접속 정보(databaseEnv)와 같이 보냅니다");
            }
        }
        String secret = blankToNull(token);
        if (secret != null && !secret.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("token 형식이 올바르지 않습니다");
        }

        return new DeployJob(
                idValue,
                uri.toString(),
                branchValue,
                secret,
                name,
                port,
                health,
                dir,
                blankToNull(file),
                cleanEnv(env),
                engine,
                canary,
                cleanMigrations(migrations),
                cleanEnv(databaseEnv),
                engine == null || "cloud".equals(mode) ? null : mode,
                url,
                importing ? Boolean.TRUE : null);
    }

    private static URI parseRepo(String repo) {
        URI uri;
        try {
            uri = URI.create(repo);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("repoUrl 이 올바르지 않습니다");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("repoUrl 은 https://github.com/{owner}/{repo} 여야 합니다");
        }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("repoUrl 호스트는 github.com 이어야 합니다");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("repoUrl 에 인증 정보를 넣지 않습니다. token 필드를 쓰세요");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("repoUrl 은 클론 주소만 받습니다");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length != 3 || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("repoUrl 은 https://github.com/{owner}/{repo} 여야 합니다");
        }
        return uri;
    }

    private static Map<String, String> cleanEnv(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > 32) {
            throw new IllegalArgumentException("env 가 너무 많습니다");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Z_][A-Z0-9_]{0,60}")) {
                throw new IllegalArgumentException("env 키가 올바르지 않습니다");
            }
            if ("APP_COLOR".equals(key) || "APP_VERSION".equals(key)) {
                throw new IllegalArgumentException(key + " 는 에이전트가 슬롯에 맞춰 넣습니다");
            }
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("env 값에 줄바꿈을 넣을 수 없습니다");
            }
            clean.put(key, value);
        });
        return Map.copyOf(clean);
    }

    private static Map<String, String> cleanMigrations(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > 64) {
            throw new IllegalArgumentException("migrations 가 너무 많습니다");
        }
        Map<String, String> clean = new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (name == null || !name.matches("[VUR][^/\\\\]*__[^/\\\\]*\\.sql") || name.contains("..")) {
                throw new IllegalArgumentException("migrations 파일명이 올바르지 않습니다");
            }
            String sql = entry.getValue() == null ? "" : entry.getValue();
            bytes += sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 900 * 1024) {
                throw new IllegalArgumentException("migrations 합계가 900KiB 를 넘습니다");
            }
            clean.put(name, sql);
        }
        return Map.copyOf(clean);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 이 필요합니다");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
