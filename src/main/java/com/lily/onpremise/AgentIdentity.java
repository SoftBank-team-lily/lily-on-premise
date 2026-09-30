package com.lily.onpremise;

import com.lily.onpremise.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AgentIdentity {

    private final String id;

    public AgentIdentity(AgentProperties properties) {
        String configured = properties.id();
        this.id = configured == null || configured.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : configured;
    }

    public String id() {
        return id;
    }
}
