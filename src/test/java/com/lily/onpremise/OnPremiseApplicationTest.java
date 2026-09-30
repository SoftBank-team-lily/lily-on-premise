package com.lily.onpremise;

import com.lily.onpremise.api.JobController;
import com.lily.onpremise.expose.LocalExposure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 빈 연결이 깨지지 않았는지. 터널과 컨트롤 플레인에는 붙지 않는다. */
@SpringBootTest(properties = {
        "lily.agent.proxy-port=0",
        "lily.agent.cloudflare.enabled=false",
        "lily.agent.control-plane-url="
})
class OnPremiseApplicationTest {

    @Autowired
    JobController jobs;

    @Autowired
    LocalExposure exposure;

    @Test
    void 기동하면_프록시_주소가_공개_주소가_된다() {
        assertThat(jobs).isNotNull();
        assertThat(exposure.publicUrl()).startsWith("http://127.0.0.1:");
    }
}
