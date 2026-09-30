package com.lily.onpremise.runtime;

import com.lily.onpremise.system.Commands;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CliContainerRuntime implements ContainerRuntime {

    private final Commands commands;

    public CliContainerRuntime(Commands commands) {
        this.commands = commands;
    }

    @Override
    public void build(Path context, String image) {
        commands.run(List.of("docker", "build", "-t", image, context.toString()), context);
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
}
