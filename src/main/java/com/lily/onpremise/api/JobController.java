package com.lily.onpremise.api;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.AgentService;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.schema.pgroll.AgentPgroll;
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
    private final AgentPgroll pgroll;
    private final SlotBook slots;

    public JobController(
            AgentService service, AgentIdentity identity, ControlSession session, TrafficSwitch traffic,
            AgentPgroll pgroll, SlotBook slots) {
        this.pgroll = pgroll;
        this.slots = slots;
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

    @GetMapping("/api/apps/{appName}/home")
    public ResponseEntity<HomeCutover.Status> home(@PathVariable String appName) {
        HomeCutover.Status status = service.homeStatus();
        if (status.appName() != null && !status.appName().isBlank() && !status.appName().equals(appName)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /** 공개 주소의 거점을 옮긴다. 이미 그 거점이면 200, 진행을 시작하면 202 */
    @PostMapping("/api/apps/{appName}/home")
    public ResponseEntity<HomeCutover.Status> move(@PathVariable String appName, @RequestBody HomeBody body) {
        HomeCutover.Status status = service.home(appName, body == null ? null : body.home(), null,
                body != null && Boolean.TRUE.equals(body.migrateDatabase()));
        HttpStatus code = status.already() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(code).body(status);
    }

    /** @param migrateDatabase 앱 DB 도 옮긴다 (클라우드로: 내 PC → RDS, 온프레미스로: RDS → 내 PC) */
    public record HomeBody(String home, Boolean migrateDatabase) {
    }

    /** 직전 슬롯으로 프록시를 되돌린다. 스키마는 그대로 둔다 */
    @PostMapping("/api/apps/{appName}/rollback")
    public ResponseEntity<JobRecord> rollback(@PathVariable String appName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.rollback(appName, null));
    }

    /** 스키마 이력과 pgroll 롤백 창. lily-cicd GET /api/deployments/{app}/schema 와 같은 모양 */
    @GetMapping("/api/apps/{appName}/schema")
    public Map<String, Object> schema(@PathVariable String appName) {
        return pgroll.status(appName, slots.currentRelease(appName), slots.previousRelease(appName));
    }

    /** 롤백 창을 바로 닫는다. 이후에는 스키마를 되돌릴 수 없다 */
    @PostMapping("/api/apps/{appName}/schema/complete")
    public Map<String, Object> completeSchema(@PathVariable String appName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", appName);
        body.put("result", pgroll.completeNow(appName));
        return body;
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
