package com.lily.onpremise;

import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.runtime.SlotRecovery;
import com.lily.onpremise.session.ControlSession;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 프로세스가 뜨면 공개 입구를 먼저 열고, 떠 있던 앱 슬롯과 버스팅을 다시 붙인 뒤, 컨트롤 플레인에 붙는다. */
@Component
public class AgentLifecycle implements ApplicationRunner {

    private final LocalExposure exposure;
    private final ControlSession session;
    private final HomeCutover cutover;
    private final SlotRecovery recovery;
    private final CloudBurst burst;

    public AgentLifecycle(LocalExposure exposure, ControlSession session, HomeCutover cutover, SlotRecovery recovery,
                          CloudBurst burst) {
        this.exposure = exposure;
        this.session = session;
        this.cutover = cutover;
        this.recovery = recovery;
        this.burst = burst;
    }

    @Override
    public void run(ApplicationArguments args) {
        exposure.open();
        // 거점(cutover.restore)을 먼저 알아야 버스팅이 클라우드 레플리카를 움직여도 되는지 안다
        Optional<String> restored = recovery.recover();
        cutover.restore();
        restored.map(SlotRecovery::appOf).ifPresent(burst::recover);
        session.connectIfConfigured();
    }
}
