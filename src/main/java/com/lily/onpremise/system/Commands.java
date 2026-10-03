package com.lily.onpremise.system;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** git, docker, cloudflared 를 쉘 없이 실행한다. 인자에 공백이 있어도 쉘이 해석하지 않는다. */
public interface Commands {

    void run(List<String> command, Path workDir);

    /** 표준 출력을 돌려준다. 종료 코드가 0 이 아니면 예외 */
    default String output(List<String> command) {
        throw new UnsupportedOperationException("output");
    }

    /** 프로세스를 살려 두고 한 줄씩 넘긴다. 에이전트가 끝날 때 같이 끈다. */
    void start(List<String> command, Consumer<String> lines);

    /** {@link #start} 와 같고, 돌려준 Runnable 로 그 프로세스만 끈다. 기본 구현은 끄지 못한다 */
    default Runnable startStoppable(List<String> command, Consumer<String> lines) {
        start(command, lines);
        return () -> {
        };
    }

    void close();

    /** owner 스레드가 기다리고 있는 프로세스({@link #run}·{@link #output})를 끊는다. 배포 취소가 부른다 */
    default void interrupt(Thread owner) {
    }
}
