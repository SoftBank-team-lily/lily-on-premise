package com.lily.onpremise.expose;

/** 후보 슬롯이 트래픽을 받아도 되는지. 프록시가 아니라 컨테이너 포트에 직접 묻는다. */
public interface Readiness {

    void await(int port, String path);
}
