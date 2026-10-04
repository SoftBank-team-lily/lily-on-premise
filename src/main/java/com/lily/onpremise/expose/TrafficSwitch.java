package com.lily.onpremise.expose;

/**
 * 공개 주소는 바꾸지 않는다. 그 주소 뒤에서 어느 슬롯이 요청을 받을지만 바꾼다.
 * 클라우드 경로의 Service selector 와 같은 자리다.
 */
public interface TrafficSwitch {

    void route(int upstreamPort);

    /** 아직 아무도 받지 않으면 0 */
    int upstreamPort();

    String publicUrl();

    /** 요청의 percent% 를 port 로 보낸다. port 0 이면 나누지 않는다. {@link #route} 하면 풀린다 */
    default void split(int port, int percent) {
    }

    default UpstreamProxy.SplitCount splitCount() {
        return UpstreamProxy.SplitCount.NONE;
    }
}
