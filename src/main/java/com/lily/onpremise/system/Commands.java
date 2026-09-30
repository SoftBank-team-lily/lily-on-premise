package com.lily.onpremise.system;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** git, docker, cloudflared 를 쉘 없이 실행한다. 인자에 공백이 있어도 쉘이 해석하지 않는다. */
public interface Commands {

    void run(List<String> command, Path workDir);

    /** 프로세스를 살려 두고 한 줄씩 넘긴다. 에이전트가 끝날 때 같이 끈다. */
    void start(List<String> command, Consumer<String> lines);

    void close();
}
