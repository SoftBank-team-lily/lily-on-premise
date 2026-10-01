package com.lily.onpremise.api;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.AgentService;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.session.ControlSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class JobController {

    private final AgentService service;
    private final AgentIdentity identity;
    private final ControlSession session;
    private final TrafficSwitch traffic;

    public JobController(
            AgentService service, AgentIdentity identity, ControlSession session, TrafficSwitch traffic) {
        this.service = service;
        this.identity = identity;
        this.session = session;
        this.traffic = traffic;
    }

    /** 컨트롤 플레인 없이 이 머신에서 같은 파이프라인을 돌릴 때 쓴다. */
    @PostMapping("/api/jobs")
    public ResponseEntity<JobRecord> create(@RequestBody DeployJob job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.accept(job));
    }

    /** 직전 슬롯으로 프록시를 되돌린다. 스키마는 그대로 둔다 */
    @PostMapping("/api/apps/{appName}/rollback")
    public ResponseEntity<JobRecord> rollback(@PathVariable String appName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.rollback(appName, null));
    }

    @GetMapping("/api/jobs")
    public List<JobRecord> history() {
        return service.history();
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<JobRecord> get(@PathVariable String id) {
        return service.get(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/agent")
    public Map<String, Object> agent() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", identity.id());
        body.put("connected", session.connected());
        body.put("publicUrl", traffic.publicUrl());
        return body;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
