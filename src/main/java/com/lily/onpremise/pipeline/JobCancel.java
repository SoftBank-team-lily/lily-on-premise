package com.lily.onpremise.pipeline;

/**
 * 파이프라인이 배포 취소를 보는 곳. 트래픽을 새 슬롯으로 바꾸기 직전({@link #commit})까지만 취소를 받는다.
 */
public interface JobCancel {

    JobCancel NONE = new JobCancel() {
        @Override
        public boolean requested(String id) {
            return false;
        }

        @Override
        public boolean commit(String id) {
            return true;
        }
    };

    /** 이 잡의 취소를 받았다 */
    boolean requested(String id);

    /** 취소를 받았으면 멈춘다. 단계 사이에서 부른다 */
    default void check(String id) {
        if (requested(id)) {
            throw new IllegalStateException("cancelled");
        }
    }

    /**
     * 트래픽을 바꾸기 직전. 이후의 취소는 받지 않는다.
     *
     * @return 취소가 먼저 왔으면 거짓 (바꾸지 말고 후보를 지운다)
     */
    boolean commit(String id);
}
