package com.lily.onpremise.analyze;

/**
 * @param stack  제공된 Dockerfile 은 스택을 단정하지 않는다
 */
public record DockerfilePlan(String content, Origin origin, Stack stack) {

    public enum Origin { PROVIDED, EXISTING, GENERATED }

    public enum Stack { GRADLE, MAVEN, NODE, PYTHON, DOCKERFILE, UNKNOWN }
}
