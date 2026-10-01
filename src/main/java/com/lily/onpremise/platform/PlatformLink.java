package com.lily.onpremise.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.pipeline.DatabaseAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 플랫폼 연결. 사용자 PC 에는 에이전트 토큰만 있고, 공개 주소와 DB 터널에 필요한 것은 컨트롤 플레인이 준다.
 *
 * <pre>
 * 에이전트 → hello   {..., "platform": true, "sshPublicKey": "ssh-ed25519 ...", "databaseHost": "172.17.0.1", "databasePort": 15432}
 * builder  → welcome {"type":"welcome","tunnelAgentId":"agent-{key}",
 *                     "cloudflare":{"accountId":"..","zoneId":"..","zoneName":"lilycloud.kr"},
 *                     "database":{"sshHost":"..","sshUser":"lily-tunnel","remoteHost":"..","remotePort":5432,"certificate":"ssh-ed25519-cert-v01@openssh.com ..."}}
 * </pre>
 *
 * cloudflare 나 database 가 없으면 그 기능은 플랫폼이 주지 않는 것이다 (quick tunnel, DB 없는 앱만).
 */
@Component
public class PlatformLink {

    private static final Logger log = LoggerFactory.getLogger(PlatformLink.class);

    private final AgentProperties properties;
    private final LocalExposure exposure;
    private final DatabaseAccess databases;

    public PlatformLink(AgentProperties properties, LocalExposure exposure, DatabaseAccess databases) {
        this.properties = properties;
        this.exposure = exposure;
        this.databases = databases;
    }

    /** hello 에 더할 값. 플랫폼 연결이 아니면 비어 있다 */
    public Map<String, Object> hello() {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (properties.platformExposure()) {
            extra.put("platform", true);
        }
        if (databases instanceof PlatformDatabase db) {
            try {
                extra.put("sshPublicKey", db.publicKey());
                extra.put("databaseHost", db.bindHost());
                extra.put("databasePort", db.bindPort());
            } catch (RuntimeException e) {
                log.warn("db tunnel key unavailable: {}", e.getMessage());
            }
        }
        return extra;
    }

    /**
     * welcome 을 적용한다. 터널 생성은 소켓으로 Cloudflare 호출을 주고받으므로 수신 스레드 밖에서 한다.
     */
    public void welcome(JsonNode message) {
        JsonNode db = message.path("database");
        if (databases instanceof PlatformDatabase platformDb && db.isObject()) {
            try {
                platformDb.configure(
                        db.path("sshHost").asText(),
                        db.path("sshUser").asText("lily-tunnel"),
                        db.path("remoteHost").asText(),
                        db.path("remotePort").asInt(5432),
                        db.path("certificate").asText());
                log.info("platform db tunnel certificate received");
            } catch (RuntimeException e) {
                log.warn("platform db tunnel rejected: {}", e.getMessage());
            }
        }
        JsonNode cf = message.path("cloudflare");
        String[] zone = cf.isObject()
                ? new String[] {cf.path("accountId").asText(), cf.path("zoneId").asText(), cf.path("zoneName").asText()}
                : null;
        String tunnelAgentId = message.path("tunnelAgentId").asText("");
        Thread.ofVirtual().name("lily-platform").start(() -> exposure.attachPlatform(tunnelAgentId, zone));
    }
}
