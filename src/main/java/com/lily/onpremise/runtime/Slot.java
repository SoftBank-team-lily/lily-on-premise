package com.lily.onpremise.runtime;

/** 한 앱의 로컬 슬롯. 클라우드 경로의 blue/green Deployment 와 같은 역할이다. */
public enum Slot {
    BLUE, GREEN;

    public Slot other() {
        return this == BLUE ? GREEN : BLUE;
    }
}
