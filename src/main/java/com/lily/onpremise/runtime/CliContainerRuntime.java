package com.lily.onpremise.runtime;

import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 사용자 레포의 빌드와 실행을 이 PC 의 한도 안에 둔다.
 * 호스트 디렉터리와 Docker 소켓은 넘기지 않는다. 앱 포트는 루프백만 연다.
 */
public final class CliContainerRuntime implements ContainerRuntime {

    private final Commands commands;
    private final AgentProperties.Sandbox sandbox;
    /** 이 Docker 가 buildx {@code --resource} 를 받는다. 거절하면 한 번만 빼고 다시 빌드한다 */
    private volatile boolean buildResource = true;

    public CliContainerRuntime(Commands commands, AgentProperties.Sandbox sandbox) {
        this.commands = commands;
        this.sandbox = sandbox;
    }

    @Override
    public void build(Path context, String image, boolean generated) {
        try {
            commands.run(buildCommand(context, image, generated, buildResource), context);
        } catch (RuntimeException e) {
            if (buildResource && unknownResourceFlag(e)) {
                buildResource = false;
                commands.run(buildCommand(context, image, generated, false), context);
                return;
            }
            throw e;
        }
    }

    @Override
    public void start(String name, String image, int hostPort, int containerPort, Map<String, String> env) {
        stop(name);
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(name);
        command.add("-p");
        command.add("127.0.0.1:" + hostPort + ":" + containerPort);
        command.add("--memory");
        command.add(sandbox.memory());
        command.add("--memory-swap");
        command.add(sandbox.memory());
        command.add("--cpus");
        command.add(sandbox.cpus());
        command.add("--pids-limit");
        command.add(Integer.toString(sandbox.pids()));
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--security-opt");
        command.add("no-new-privileges:true");
        if (containerPort > 0 && containerPort < 1024) {
            command.add("--cap-add");
            command.add("NET_BIND_SERVICE");
        }
        if (env.values().stream().anyMatch(value -> value != null && value.contains("host.docker.internal"))) {
            // 사용자 PC 의 DB(localhost). Docker Desktop 은 원래 풀리고, Linux 는 이 연결이 있어야 풀린다
            command.add("--add-host=host.docker.internal:host-gateway");
        }
        env.forEach((key, value) -> {
            command.add("-e");
            command.add(key + "=" + value);
        });
        command.add(image);
        commands.run(command, null);
    }

    @Override
    public void stop(String name) {
        try {
            commands.run(List.of("docker", "rm", "-f", name), null);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("no such container")) {
                return;
            }
            throw e;
        }
    }

    /**
     * 생성된 Dockerfile 은 {@code RUN --network=default} 로 의존성만 받는다.
     * 저장소 Dockerfile 은 어느 RUN 이 네트워크를 쓰는지 알 수 없어서 기본 네트워크를 유지한다.
     */
    private List<String> buildCommand(Path context, String image, boolean generated, boolean resource) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("build");
        if (resource) {
            command.add("--resource");
            command.add("memory=" + sandbox.memory());
            command.add("--resource");
            command.add("cpu-quota=" + sandbox.cpuQuota());
        }
        command.add("--ulimit");
        command.add("nproc=" + sandbox.pids() + ":" + sandbox.pids());
        if (generated) {
            command.add("--network");
            command.add("none");
        }
        command.add("-t");
        command.add(image);
        command.add(context.toString());
        return command;
    }

    static boolean unknownResourceFlag(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("unknown flag: --resource");
    }
}
