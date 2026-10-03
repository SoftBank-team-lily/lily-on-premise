package com.lily.onpremise.job;

import com.lily.onpremise.pipeline.JobCancel;
import com.lily.onpremise.system.Commands;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 컨트롤 플레인이 보낸 배포 취소 ({"type":"cancel","id"}).
 *
 * <p>도는 잡이면 그 스레드가 기다리는 git·docker 프로세스를 끊고 스레드를 깨운다 (헬스·판정 대기). 파이프라인은
 * 단계 사이에서 {@link #check} 로 멈추고, 트래픽을 바꾸기 직전({@link #commit}) 이후의 취소는 받지 않는다.
 * 잡이 오기 전에 온 취소도 기억한다 (builder 가 잡을 보내기 직전에 취소한 경우).
 */
public final class JobCancels implements JobCancel {

    private final Commands commands;
    private final Set<String> requested = ConcurrentHashMap.newKeySet();
    private final Set<String> committed = ConcurrentHashMap.newKeySet();
    private final Map<String, Thread> running = new ConcurrentHashMap<>();

    public JobCancels(Commands commands) {
        this.commands = commands;
    }

    /** @return 받았으면 참. 이미 트래픽을 바꾼 잡이면 거짓 */
    public synchronized boolean request(String id) {
        if (id == null || id.isBlank() || committed.contains(id)) {
            return false;
        }
        requested.add(id);
        Thread thread = running.get(id);
        if (thread != null) {
            commands.interrupt(thread);
            thread.interrupt();
        }
        return true;
    }

    /** 이 스레드가 잡을 돌기 시작한다 */
    public synchronized void begin(String id) {
        running.put(id, Thread.currentThread());
    }

    /** 잡이 끝났다. 취소가 남긴 interrupt 표시를 지운다 (스레드는 풀로 돌아간다) */
    public synchronized void end(String id) {
        running.remove(id);
        Thread.interrupted();
    }

    @Override
    public boolean requested(String id) {
        return requested.contains(id);
    }

    @Override
    public synchronized boolean commit(String id) {
        if (requested.contains(id)) {
            return false;
        }
        committed.add(id);
        return true;
    }
}
