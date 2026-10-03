package com.lily.onpremise.runtime;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    /** 프로세스 메모리에만 둔다. 에이전트가 재시작하면 이전 이미지는 남아도 롤백 기록은 없다 */
    private volatile Release current;
    private volatile Release previous;

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

    /** 전환에 성공한 슬롯. 직전 슬롯은 롤백할 때 같은 이미지로 다시 띄운다 */
    public void published(Release release) {
        if (current != null && current.app().equals(release.app())) {
            previous = current;
        } else {
            previous = null;
        }
        current = release;
        active.put(release.app(), Slot.valueOf(release.slot().toUpperCase(Locale.ROOT)));
    }

    public Optional<Release> currentRelease(String app) {
        return current != null && current.app().equals(app) ? Optional.of(current) : Optional.empty();
    }

    public Optional<Release> previousRelease(String app) {
        return previous != null && previous.app().equals(app) ? Optional.of(previous) : Optional.empty();
    }

    /** 롤백으로 직전 슬롯이 트래픽을 받는다. 방금 내리기 시작한 슬롯이 previous 가 된다 */
    public void rolledBack(String app) {
        if (previous == null || current == null || !previous.app().equals(app) || !current.app().equals(app)) {
            throw new IllegalStateException("되돌릴 슬롯이 없다");
        }
        Release back = previous;
        previous = current;
        current = back;
        active.put(app, Slot.valueOf(current.slot().toUpperCase(Locale.ROOT)));
    }

    /** 앱을 지웠다. 활성 슬롯과 롤백 기록에서 뺀다 */
    public void forget(String app) {
        active.remove(app);
        if (previous != null && previous.app().equals(app)) {
            previous = null;
        }
        if (current != null && current.app().equals(app)) {
            current = null;
            previous = null;
        }
    }

    /**
     * 한 번 공개했던 슬롯. 컨테이너를 지운 뒤에도 이미지 태그로 다시 띄울 수 있다.
     */
    public record Release(String app, String slot, String image, int hostPort, int containerPort,
                          String healthPath, Map<String, String> env) {
    }

    public static String container(String app, Slot slot) {
        return app + "-" + slot.name().toLowerCase();
    }
}
