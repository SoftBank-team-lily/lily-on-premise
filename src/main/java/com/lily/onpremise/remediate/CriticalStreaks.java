package com.lily.onpremise.remediate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** CRITICAL 이 연속 기준에 닿은 틱에만 보내고, 등급이 내려가면 다시 센다. */
final class CriticalStreaks {

    private final Map<String, Integer> count = new HashMap<>();
    private final Set<String> sent = new HashSet<>();

    boolean ready(String key, boolean critical, int consecutive) {
        if (consecutive < 1) {
            return false;
        }
        if (!critical) {
            count.remove(key);
            sent.remove(key);
            return false;
        }
        int next = count.merge(key, 1, (prev, one) -> Math.min(prev + one, consecutive));
        return next >= consecutive && !sent.contains(key);
    }

    void ack(String key) {
        sent.add(key);
    }
}
