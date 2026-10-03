package com.lily.onpremise.job;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 에이전트가 기억하는 배포 한 건. 토큰과 Dockerfile 본문은 담지 않는다.
 */
public final class JobRecord {

    public enum Status {
        QUEUED, CHECKOUT, ANALYZE, BUILDING, STARTING, HEALTH, JUDGING, SWITCHING, SUCCEEDED, FAILED,
        /** 컨트롤 플레인이 취소했다. 트래픽은 이전 슬롯 그대로 */
        CANCELLED
    }

    private final String id;
    private final String appName;
    private final String repoUrl;
    private final String branch;
    private final Instant createdAt;
    private final List<String> logs;
    private volatile Instant updatedAt;
    private volatile Status status;
    private volatile String url;
    private volatile String activeSlot;

    /** 슬롯을 되돌릴 때. 레포를 다시 받지 않는다 */
    public JobRecord(String id, String appName) {
        this.id = id;
        this.appName = appName;
        this.repoUrl = "";
        this.branch = "";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = Status.QUEUED;
        this.logs = new CopyOnWriteArrayList<>();
    }

    public JobRecord(DeployJob job) {
        this.id = job.id();
        this.appName = job.appName();
        this.repoUrl = job.repoUrl();
        this.branch = job.branch();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = Status.QUEUED;
        this.logs = new CopyOnWriteArrayList<>();
    }

    public void update(Status status, String line) {
        this.status = status;
        logs.add(line);
        updatedAt = Instant.now();
    }

    public void url(String url) {
        this.url = url;
    }

    public void activeSlot(String activeSlot) {
        this.activeSlot = activeSlot;
    }

    public String lastLine() {
        return logs.isEmpty() ? "" : logs.get(logs.size() - 1);
    }

    public String getId() { return id; }
    public String getAppName() { return appName; }
    public String getRepoUrl() { return repoUrl; }
    public String getBranch() { return branch; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Status getStatus() { return status; }
    public String getUrl() { return url; }
    public String getActiveSlot() { return activeSlot; }
    public List<String> getLogs() { return List.copyOf(logs); }
}
