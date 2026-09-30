package com.lily.onpremise.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이 에이전트의 공개 주소는 하나다. 그래서 포트를 쓰는 앱도 하나다.
 * 다른 앱 잡이 오면 이전 앱 컨테이너 이름을 돌려주고 기록에서 뺀다.
 */
@Component
public class SlotBook {

    private final Map<String, Slot> active = new ConcurrentHashMap<>();

    public Optional<Slot> active(String app) {
        return Optional.ofNullable(active.get(app));
    }

    public void commit(String app, Slot slot) {
        active.put(app, slot);
    }

    /** 지금 트래픽을 받는 컨테이너. 맵에 앱이 하나일 때의 활성 슬롯이다. */
    public Optional<String> liveContainer() {
        return active.entrySet().stream()
                .findFirst()
                .map(entry -> container(entry.getKey(), entry.getValue()));
    }

    /** 트래픽을 받는 컨테이너는 남기고, 포트를 차지할 수 있는 나머지 이름만 돌려준다. */
    public List<String> containersExcept(String keep) {
        List<String> names = new ArrayList<>();
        for (String app : active.keySet()) {
            for (Slot slot : Slot.values()) {
                String name = container(app, slot);
                if (!name.equals(keep)) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    public void retain(String app) {
        active.keySet().removeIf(key -> !key.equals(app));
    }

    public static String container(String app, Slot slot) {
        return app + "-" + slot.name().toLowerCase();
    }
}
