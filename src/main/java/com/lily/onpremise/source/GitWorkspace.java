package com.lily.onpremise.source;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.system.Commands;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class GitWorkspace implements Workspace {

    private final Commands commands;
    private final Path root;

    public GitWorkspace(Commands commands, Path root) {
        this.commands = commands;
        this.root = root;
    }

    @Override
    public Path checkout(DeployJob job) {
        Path dest = root.resolve(job.id());
        delete(dest);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("workspace 를 만들지 못했습니다", e);
        }
        String remote = withToken(job.repoUrl(), job.token());
        try {
            commands.run(List.of(
                    "git", "clone", "--depth", "1", "--branch", job.branch(), remote, dest.toString()), root);
        } catch (RuntimeException e) {
            throw new IllegalStateException(scrub(e.getMessage(), job.token()), e);
        }
        return dest;
    }

    /** 토큰은 URL 사용자 정보에만 넣고, 로그로 나가는 문자열에는 남기지 않는다. */
    static String withToken(String repoUrl, String token) {
        if (token == null || token.isBlank()) {
            return repoUrl;
        }
        URI uri = URI.create(repoUrl);
        try {
            return new URI("https", "x-access-token:" + token, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
                    .toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("repoUrl 이 올바르지 않습니다", e);
        }
    }

    static String scrub(String message, String token) {
        String text = message == null ? "" : message;
        if (token != null && !token.isBlank()) {
            text = text.replace(token, "***");
        }
        return text.replaceAll("(https://)[^@\\s/]+@", "$1***@");
    }

    private static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("workspace 를 비우지 못했습니다", e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("workspace 를 비우지 못했습니다", e);
        }
    }
}
