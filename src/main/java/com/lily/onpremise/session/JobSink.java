package com.lily.onpremise.session;

import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;

public interface JobSink {

    JobRecord accept(DeployJob job);

    /** 직전 온프레미스 슬롯으로 프록시를 되돌린다. id 가 없으면 에이전트가 만든다 */
    JobRecord rollback(String app, String id);

    /** 공개 주소의 거점을 cloud 또는 onprem 으로 옮긴다 */
    default HomeCutover.Status home(String app, String target, String id) {
        return home(app, target, id, false);
    }

    /** @param migrateDatabase 앱 DB 도 옮긴다 (클라우드로: 내 PC → RDS, 온프레미스로: RDS → 내 PC) */
    HomeCutover.Status home(String app, String target, String id, boolean migrateDatabase);

    /**
     * 앱을 이 PC 에서 지운다 (슬롯 컨테이너, 이미지, 버스팅·거점 기억). 공개 주소와 클라우드 쪽은 builder 가 지운다.
     *
     * @param database true 면 이 PC 의 DB 컨테이너에 있는 앱 DB 와 계정도 지운다. 사용자가 준 외부 DB 는 건드리지 않는다
     */
    JobRecord remove(String app, String id, boolean database);

    /**
     * 진행 중인 배포를 멈춘다. 트래픽을 새 슬롯으로 바꾸기 전이면 후보를 지우고 CANCELLED 로 끝난다.
     * 아직 오지 않은 잡 id 면 기억했다가 그 잡이 오면 바로 CANCELLED 로 끝낸다
     *
     * @return 받았으면 참. 이미 트래픽을 바꾼 잡이면 거짓
     */
    default boolean cancel(String id) {
        return false;
    }
}
