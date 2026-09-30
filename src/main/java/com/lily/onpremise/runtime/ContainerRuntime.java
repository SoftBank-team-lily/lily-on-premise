package com.lily.onpremise.runtime;

import java.nio.file.Path;
import java.util.Map;

/** 로컬 Docker. 이미지를 레지스트리에 올리지 않는다. 이 머신 안에서만 슬롯을 띄운다. */
public interface ContainerRuntime {

    void build(Path context, String image);

    void start(String name, String image, int hostPort, int containerPort, Map<String, String> env);

    void stop(String name);
}
