package com.lily.onpremise.expose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lily.onpremise.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareHostnameProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 새_터널과_프록시된_CNAME_을_만들고_와일드카드_인증서가_있으면_주문하지_않는다() {
        ScriptedCloudflare api = new ScriptedCloudflare();
        api.packs = packs("active", "lily.dev", "*.lily.dev");
        CloudflareHostnameProvisioner provisioner = provisioner(api);

        assertThat(provisioner.openTunnel("Ab12")).isEqualTo("created-token");
        assertThat(provisioner.ensureHostname("blog", 8099)).isEqualTo("https://blog.lily.dev");

        assertThat(api.calls).anyMatch(call -> call.startsWith("POST /accounts/acct/cfd_tunnel"));
        assertThat(api.calls).anyMatch(call -> call.contains("/configurations"));
        assertThat(api.calls).anyMatch(call -> call.equals("POST /zones/zone/dns_records"));
        assertThat(api.calls).noneMatch(call -> call.contains("/order"));
        assertThat(api.dnsBody.path("proxied").asBoolean()).isTrue();
        assertThat(api.dnsBody.path("name").asText()).isEqualTo("blog.lily.dev");
        assertThat(api.dnsBody.path("content").asText()).isEqualTo("tid.cfargotunnel.com");
        assertThat(api.ingress.path("config").path("ingress").get(0).path("service").asText())
                .isEqualTo("http://127.0.0.1:8099");
        assertThat(api.ingress.path("config").path("ingress").get(1).path("service").asText())
                .isEqualTo("http_status:404");
    }

    @Test
    void 있는_터널과_레코드는_다시_만들지_않는다() {
        ScriptedCloudflare api = new ScriptedCloudflare();
        api.tunnels = MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                .put("id", "tid")
                .put("name", "lily-ab12")
                .putNull("deleted_at"));
        api.records = MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                .put("id", "rec")
                .put("content", "tid.cfargotunnel.com")
                .put("proxied", true));
        api.packs = packs("active", "*.lily.dev");
        CloudflareHostnameProvisioner provisioner = provisioner(api);

        assertThat(provisioner.openTunnel("ab12")).isEqualTo("issued-token");
        assertThat(provisioner.ensureHostname("blog", 8099)).isEqualTo("https://blog.lily.dev");

        assertThat(api.calls).noneMatch(call -> call.startsWith("POST /accounts/acct/cfd_tunnel"));
        assertThat(api.calls).anyMatch(call -> call.endsWith("/token"));
        assertThat(api.calls).noneMatch(call -> call.startsWith("POST /zones/zone/dns_records"));
        assertThat(api.calls).noneMatch(call -> call.startsWith("PUT /zones/zone/dns_records"));
    }

    @Test
    void 인증서가_호스트를_덮지_않으면_Advanced_Certificate_를_주문한다() {
        ScriptedCloudflare api = new ScriptedCloudflare();
        api.packs = packs("active", "lily.dev");
        CloudflareHostnameProvisioner provisioner = provisioner(api);

        provisioner.openTunnel("ab12");
        provisioner.ensureHostname("blog", 8099);

        assertThat(api.calls).anyMatch(call -> call.endsWith("/ssl/certificate_packs/order"));
        assertThat(api.orderBody.path("hosts").get(0).asText()).isEqualTo("blog.lily.dev");
        assertThat(api.orderBody.path("type").asText()).isEqualTo("advanced");
    }

    private static CloudflareHostnameProvisioner provisioner(ScriptedCloudflare api) {
        return new CloudflareHostnameProvisioner(api, new AgentProperties.Cloudflare(
                false, "", "secret-token", "acct", "zone", "Lily.dev."));
    }

    private static JsonNode packs(String status, String... hosts) {
        var pack = MAPPER.createObjectNode().put("status", status);
        var list = pack.putArray("hosts");
        for (String host : hosts) {
            list.add(host);
        }
        return MAPPER.createArrayNode().add(pack);
    }

    static final class ScriptedCloudflare implements CloudflareClient {
        final List<String> calls = new ArrayList<>();
        JsonNode tunnels = MAPPER.createArrayNode();
        JsonNode records = MAPPER.createArrayNode();
        JsonNode packs = MAPPER.createArrayNode();
        JsonNode dnsBody;
        JsonNode ingress;
        JsonNode orderBody;

        @Override
        public JsonNode call(String method, String path, JsonNode body) {
            calls.add(method + " " + path);
            if (method.equals("GET") && path.contains("/cfd_tunnel?")) {
                return tunnels;
            }
            if (method.equals("POST") && path.endsWith("/cfd_tunnel")) {
                return MAPPER.createObjectNode().put("id", "tid").put("token", "created-token");
            }
            if (path.endsWith("/token")) {
                return TextNode.valueOf("issued-token");
            }
            if (path.endsWith("/configurations")) {
                ingress = body;
                return MAPPER.createObjectNode();
            }
            if (method.equals("GET") && path.contains("/dns_records?")) {
                return records;
            }
            if (method.equals("POST") && path.endsWith("/dns_records")) {
                dnsBody = body;
                return MAPPER.createObjectNode().put("id", "rec");
            }
            if (path.endsWith("/ssl/certificate_packs")) {
                return packs;
            }
            if (path.endsWith("/order")) {
                orderBody = body;
                return MAPPER.createObjectNode().put("id", "pack");
            }
            throw new IllegalStateException("unexpected " + method + " " + path);
        }
    }
}
