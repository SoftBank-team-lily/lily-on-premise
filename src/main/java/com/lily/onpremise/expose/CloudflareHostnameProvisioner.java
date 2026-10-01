package com.lily.onpremise.expose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lily.onpremise.config.AgentProperties;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 우리 존에 {@code https://{app}.{zone}} 을 붙인다.
 * 터널 ingress, 프록시된 CNAME, 가장자리 인증서를 Cloudflare API 로 맞춘다.
 * 오리진은 로컬 프록시의 HTTP 이다. 인증서는 에이전트 머신이 아니라 Cloudflare 가장자리에 있다.
 */
public final class CloudflareHostnameProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CloudflareClient client;
    // 플랫폼 연결이면 컨트롤 플레인의 welcome 을 받은 뒤에 정해진다
    private volatile String accountId;
    private volatile String zoneId;
    private volatile String zoneName;
    private volatile String tunnelId = "";
    /** 거점이 클라우드이거나 그쪽으로 옮기는 중이면 배포가 CNAME 을 터널로 되돌리지 않는다 */
    private volatile boolean holdDns;

    public CloudflareHostnameProvisioner(CloudflareClient client, AgentProperties.Cloudflare cloudflare) {
        this.client = client;
        this.accountId = cloudflare == null ? "" : cloudflare.accountId();
        this.zoneId = cloudflare == null ? "" : cloudflare.zoneId();
        this.zoneName = cloudflare == null ? "" : cloudflare.zoneNameNormalized();
    }

    /** 컨트롤 플레인이 알려 준 계정·존. 호출은 생성자의 client(컨트롤 플레인 중계)가 한다 */
    public void configure(String accountId, String zoneId, String zoneName) {
        this.accountId = accountId == null ? "" : accountId;
        this.zoneId = zoneId == null ? "" : zoneId;
        this.zoneName = new AgentProperties.Cloudflare(false, "", "", "", "", zoneName).zoneNameNormalized();
        this.tunnelId = "";
    }

    /** 터널이 열려 앱마다 호스트이름을 붙일 수 있다 */
    public boolean configured() {
        return !tunnelId.isBlank() && !zoneName.isBlank();
    }

    /** 에이전트 터널을 만들거나 찾고, cloudflared 에 넣을 토큰을 돌려준다. */
    public String openTunnel(String agentId) {
        String name = "lily-" + label(agentId);
        JsonNode existing = findTunnel(name);
        if (existing == null) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("name", name);
            body.put("config_src", "cloudflare");
            JsonNode created = client.call("POST", "/accounts/" + accountId + "/cfd_tunnel", body);
            tunnelId = created.path("id").asText("");
            String token = text(created.path("token"));
            if (token.isBlank()) {
                token = text(client.call("GET", "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId + "/token", null));
            }
            requireToken(token);
            return token;
        }
        tunnelId = existing.path("id").asText("");
        String token = text(client.call("GET", "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId + "/token", null));
        requireToken(token);
        return token;
    }

    /**
     * 이 앱의 호스트이름을 터널과 존에 붙인다.
     * 존의 활성 인증서가 호스트를 덮으면 추가 주문은 하지 않는다.
     * 덮지 않으면 Advanced Certificate 를 주문한다.
     */
    public String ensureHostname(String appName, int proxyPort) {
        if (tunnelId.isBlank()) {
            throw new IllegalStateException("cloudflare tunnel 이 아직 없습니다");
        }
        String hostname = hostname(appName);
        putIngress(hostname, proxyPort);
        if (!holdDns) {
            point(hostname, tunnelTarget());
        }
        ensureCertificate(hostname);
        return "https://" + hostname;
    }

    /** 배포 파이프라인이 CNAME 을 건드리지 않게 한다. 터널 ingress 와 인증서는 그대로 맞춘다 */
    public void holdDns(boolean hold) {
        this.holdDns = hold;
    }

    public String hostname(String appName) {
        if (zoneName.isBlank()) {
            throw new IllegalStateException("cloudflare zone 이 없습니다");
        }
        return label(appName) + "." + zoneName;
    }

    public String tunnelTarget() {
        if (tunnelId.isBlank()) {
            throw new IllegalStateException("cloudflare tunnel 이 아직 없습니다");
        }
        return tunnelId + ".cfargotunnel.com";
    }

    /** 현재 CNAME 내용물. 레코드가 없으면 빈 문자열 */
    public String cname(String hostname) {
        JsonNode current = currentRecord(hostname);
        return current == null ? "" : current.path("content").asText("");
    }

    /**
     * CNAME 내용물만 바꾼다. 호출이 실패하면 예외이고, 레코드는 이전 값이다.
     * 내용물이 IP 이면 타입을 바꾸게 되므로 거절한다.
     */
    public void point(String hostname, String content) {
        if (content == null || content.isBlank() || ip(content)) {
            throw new IllegalArgumentException("CNAME 내용물은 호스트 이름이어야 합니다");
        }
        JsonNode current = currentRecord(hostname);
        if (current != null
                && content.equals(current.path("content").asText())
                && current.path("proxied").asBoolean(false)) {
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", "CNAME");
        body.put("name", hostname);
        body.put("content", content);
        body.put("proxied", true);
        body.put("ttl", 1);
        if (current == null) {
            client.call("POST", "/zones/" + zoneId + "/dns_records", body);
            return;
        }
        client.call("PUT", "/zones/" + zoneId + "/dns_records/" + current.path("id").asText(), body);
    }

    public static boolean ip(String value) {
        return value.indexOf(':') >= 0 || value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private JsonNode findTunnel(String name) {
        String path = "/accounts/" + accountId + "/cfd_tunnel?is_deleted=false&name=" + encode(name);
        JsonNode result = client.call("GET", path, null);
        if (result == null || !result.isArray()) {
            return null;
        }
        for (JsonNode tunnel : result) {
            if (name.equals(tunnel.path("name").asText()) && tunnel.path("deleted_at").isNull()) {
                return tunnel;
            }
        }
        return null;
    }

    private void putIngress(String hostname, int proxyPort) {
        ObjectNode rule = MAPPER.createObjectNode();
        rule.put("hostname", hostname);
        rule.put("service", "http://127.0.0.1:" + proxyPort);
        rule.putObject("originRequest");
        ObjectNode catchAll = MAPPER.createObjectNode();
        catchAll.put("service", "http_status:404");
        ArrayNode ingress = MAPPER.createArrayNode().add(rule).add(catchAll);
        ObjectNode config = MAPPER.createObjectNode();
        config.set("ingress", ingress);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("config", config);
        client.call("PUT", "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId + "/configurations", body);
    }

    private JsonNode currentRecord(String hostname) {
        JsonNode records = client.call(
                "GET", "/zones/" + zoneId + "/dns_records?type=CNAME&name=" + encode(hostname), null);
        return records != null && records.isArray() && !records.isEmpty() ? records.get(0) : null;
    }

    private void ensureCertificate(String hostname) {
        JsonNode packs = client.call("GET", "/zones/" + zoneId + "/ssl/certificate_packs", null);
        if (packs != null && packs.isArray()) {
            for (JsonNode pack : packs) {
                if (covers(pack, hostname)) {
                    return;
                }
            }
        }
        ObjectNode order = MAPPER.createObjectNode();
        order.put("type", "advanced");
        order.putArray("hosts").add(hostname);
        order.put("validation_method", "txt");
        order.put("validity_days", 90);
        order.put("certificate_authority", "google");
        client.call("POST", "/zones/" + zoneId + "/ssl/certificate_packs/order", order);
    }

    static boolean covers(JsonNode pack, String hostname) {
        String status = pack.path("status").asText("");
        if (!status.equals("active") && !status.equals("pending_validation")
                && !status.equals("pending_deployment") && !status.equals("initializing")) {
            return false;
        }
        for (JsonNode host : pack.path("hosts")) {
            if (!host.isTextual()) {
                continue;
            }
            String value = host.asText();
            if (value.equalsIgnoreCase(hostname)) {
                return true;
            }
            if (value.startsWith("*.") && oneLabelUnder(hostname, value.substring(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean oneLabelUnder(String hostname, String dotSuffix) {
        if (!hostname.toLowerCase().endsWith(dotSuffix.toLowerCase())) {
            return false;
        }
        String label = hostname.substring(0, hostname.length() - dotSuffix.length());
        return !label.isEmpty() && !label.contains(".");
    }

    static String label(String value) {
        String raw = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        raw = raw.replaceAll("^-+", "").replaceAll("-+$", "");
        if (raw.length() > 63) {
            raw = raw.substring(0, 63).replaceAll("-+$", "");
        }
        if (!raw.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalStateException("DNS 이름으로 쓸 수 없습니다");
        }
        return raw;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.path("token").asText("");
    }

    private static void requireToken(String token) {
        if (token.isBlank()) {
            throw new IllegalStateException("cloudflare tunnel 토큰을 받지 못했습니다");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
