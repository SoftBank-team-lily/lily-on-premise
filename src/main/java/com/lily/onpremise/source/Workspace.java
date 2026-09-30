package com.lily.onpremise.source;

import com.lily.onpremise.job.DeployJob;

import java.nio.file.Path;

public interface Workspace {

    /** 잡 하나의 소스를 받아 체크아웃 루트를 돌려준다. */
    Path checkout(DeployJob job);
}
