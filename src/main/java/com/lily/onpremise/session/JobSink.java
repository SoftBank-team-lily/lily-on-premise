package com.lily.onpremise.session;

import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;

public interface JobSink {

    JobRecord accept(DeployJob job);

    /** 직전 온프레미스 슬롯으로 프록시를 되돌린다. id 가 없으면 에이전트가 만든다 */
    JobRecord rollback(String app, String id);

    /** 공개 주소의 거점을 cloud 또는 onprem 으로 옮긴다 */
    HomeCutover.Status home(String app, String target, String id);
}
