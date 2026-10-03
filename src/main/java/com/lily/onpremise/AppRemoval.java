package com.lily.onpremise;

import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.database.DatabaseModes;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.Slot;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 프로젝트를 지우면 이 PC 에 남은 앱을 내린다. 공개 주소(Cloudflare)와 클라우드 대기 배포는 builder 가 지운다.
 *
 * <p>순서: 거점·버스팅 기억 → 프록시 → 슬롯 컨테이너 → 이미지 → (database) 이 PC 의 앱 DB.
 * 컨테이너는 {@code --restart unless-stopped} 라 에이전트를 멈춰도 내려가지 않으므로 여기서 지운다.
 */
@Component
public class AppRemoval {

    private static final Logger log = LoggerFactory.getLogger(AppRemoval.class);

    private final HomeCutover cutover;
    private final CloudBurst burst;
    private final TrafficSwitch traffic;
    private final SlotBook slots;
    private final ContainerRuntime runtime;
    private final Commands commands;
    private final DatabaseModes databases;

    public AppRemoval(HomeCutover cutover, CloudBurst burst, TrafficSwitch traffic, SlotBook slots,
                      ContainerRuntime runtime, Commands commands, DatabaseModes databases) {
        this.cutover = cutover;
        this.burst = burst;
        this.traffic = traffic;
        this.slots = slots;
        this.runtime = runtime;
        this.commands = commands;
        this.databases = databases;
    }

    /**
     * @param database true 면 이 PC 의 DB 컨테이너에 있는 앱 DB 와 계정도 지운다
     * @return 지운 것 (잡 상태 줄에 남긴다)
     * @throws IllegalStateException 거점을 옮기는 중이다
     */
    public List<String> remove(String app, boolean database) {
        List<String> removed = new ArrayList<>();
        cutover.forget(app);
        burst.forget(app);
        if (slots.active(app).isPresent() && traffic.upstreamPort() != 0) {
            // 공개 주소로 들어온 요청이 지운 컨테이너로 가지 않게 한다 (no upstream 503)
            traffic.route(0);
            removed.add("proxy");
        }
        for (Slot slot : Slot.values()) {
            String name = SlotBook.container(app, slot);
            runtime.stop(name);
        }
        removed.add("containers");
        slots.forget(app);
        int images = removeImages(app);
        if (images > 0) {
            removed.add("images " + images);
        }
        if (database) {
            List<String> dropped = databases.dropLocal(app);
            removed.add(dropped.isEmpty() ? "database none" : "database " + String.join(", ", dropped));
        }
        log.info("app removed: app={} removed={}", app, removed);
        return removed;
    }

    /** 이 앱으로 빌드한 이미지. 지우지 못해도 앱 삭제는 끝난다 (디스크만 남는다) */
    private int removeImages(String app) {
        try {
            String ids = commands.output(List.of("docker", "images", "-q", "lily-onprem/" + app));
            List<String> unique = Arrays.stream(ids.split("\\s+")).filter(id -> !id.isBlank()).distinct().toList();
            if (unique.isEmpty()) {
                return 0;
            }
            List<String> command = new ArrayList<>(List.of("docker", "rmi", "-f"));
            command.addAll(unique);
            commands.run(command, null);
            return unique.size();
        } catch (RuntimeException e) {
            log.warn("images not removed: app={} message={}", app, e.getMessage());
            return 0;
        }
    }
}
