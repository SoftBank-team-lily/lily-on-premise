package com.lily.onpremise.api;

import com.lily.onpremise.burst.CloudBurst;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 클라우드 버스팅 상태: 단계, 로컬·클라우드 동시 연결, 최근 이벤트 */
@RestController
public class BurstStatusController {

    private final CloudBurst burst;

    public BurstStatusController(CloudBurst burst) {
        this.burst = burst;
    }

    @GetMapping("/api/burst")
    public CloudBurst.Status status() {
        return burst.status();
    }
}
