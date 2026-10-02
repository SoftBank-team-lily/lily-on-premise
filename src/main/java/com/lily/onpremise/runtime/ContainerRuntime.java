package com.lily.onpremise.runtime;

import java.nio.file.Path;
import java.util.Map;

/**
 * 로컬 Docker. 이미지를 레지스트리에 올리지 않는다. 이 머신 안에서만 슬롯을 띄운다.
 * 빌드와 실행은 메모리·CPU·프로세스 한도 안에서 돈다. 실행 중인 앱은 capability 를 갖지 않는다.
 */
public interface ContainerRuntime {

    /**
     * @param generated 에이전트가 만든 Dockerfile. 빌드 기본 네트워크는 끄고, 그 파일의 의존성 RUN 만 연다
     */
    void build(Path context, String image, boolean generated);

    void start(String name, String image, int hostPort, int containerPort, Map<String, String> env);

    void stop(String name);
}
