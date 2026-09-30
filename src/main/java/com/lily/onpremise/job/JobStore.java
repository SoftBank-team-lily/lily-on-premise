package com.lily.onpremise.job;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 이 머신에서 받은 잡. 프로세스가 내려가면 사라진다. */
@Component
public class JobStore {

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    public void save(JobRecord record) {
        jobs.put(record.getId(), record);
    }

    public Optional<JobRecord> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<JobRecord> findAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(JobRecord::getCreatedAt).reversed())
                .toList();
    }
}
