package com.lily.onpremise;

import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.runtime.SlotRecovery;
import com.lily.onpremise.session.ControlSession;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 프로세스가 뜨면 공개 입구를 먼저 열고, 떠 있던 앱 슬롯을 다시 붙인 뒤, 컨트롤 플레인에 붙는다. */
@Component
public class AgentLifecycle implements ApplicationRunner {

    private final LocalExposure exposure;
    private final ControlSession session;
    private final HomeCutover cutover;
    private final SlotRecovery recovery;

    public AgentLifecycle(LocalExposure exposure, ControlSession session, HomeCutover cutover, SlotRecovery recovery) {
        this.exposure = exposure;
        this.session = session;
        this.cutover = cutover;
        this.recovery = recovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        exposure.open();
        recovery.recover();
        cutover.restore();
        session.connectIfConfigured();
    }
}
