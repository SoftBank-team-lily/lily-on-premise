package com.lily.onpremise.session;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;

public interface JobSink {

    JobRecord accept(DeployJob job);
}
