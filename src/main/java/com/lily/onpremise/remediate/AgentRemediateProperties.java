package com.lily.onpremise.remediate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 켜져 있을 때만 슬롯 로그를 읽고 컨트롤 플레인으로 보낸다. 기본은 꺼짐 */
@ConfigurationProperties("lily.agent.remediate")
public record AgentRemediateProperties(@DefaultValue("false") boolean enabled) {
}
