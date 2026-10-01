package com.lily.onpremise.session;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;

public interface JobSink {

    JobRecord accept(DeployJob job);

    /** 직전 온프레미스 슬롯으로 프록시를 되돌린다. id 가 없으면 에이전트가 만든다 */
    JobRecord rollback(String app, String id);
}
