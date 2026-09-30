package com.lily.onpremise.session;

import com.lily.onpremise.job.JobRecord;

/** 에이전트가 컨트롤 플레인으로 여는 연결. 잡은 이 연결로 들어오고, 단계 로그도 이 연결로 나간다. */
public interface ControlSession {

    void connectIfConfigured();

    void report(JobRecord record);

    boolean connected();
}
