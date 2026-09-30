package com.lily.onpremise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 에이전트 한 대가 깔리는 머신에 대한 값.
 * 배포 요청마다 바뀌는 값은 {@code DeployJob} 이 가져온다.
 *
 * @param proxyPort  공개 주소가 가리키는 로컬 포트. blue/green 컨테이너 포트와 다르다
 * @param publicUrl  API 없이 named tunnel 을 쓸 때의 고정 주소
 */
@ConfigurationProperties("lily.agent")
public record AgentProperties(
        @DefaultValue("") String id,
        @DefaultValue("") String controlPlaneUrl,
        @DefaultValue("8099") int proxyPort,
        @DefaultValue("18080") int bluePort,
        @DefaultValue("18081") int greenPort,
        @DefaultValue("120") int healthTimeoutSeconds,
        @DefaultValue("") String publicUrl,
        @DefaultValue("") String workspace,
        @DefaultValue Cloudflare cloudflare) {

    public record Cloudflare(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token,
            @DefaultValue("") String apiToken,
            @DefaultValue("") String accountId,
            @DefaultValue("") String zoneId,
            @DefaultValue("") String zoneName) {

        /** 네 값이 모두 있으면 우리 존에 https 호스트를 만든다. */
        public boolean apiConfigured() {
            return filled(apiToken) && filled(accountId) && filled(zoneId) && filled(zoneName);
        }

        public boolean partialApi() {
            int present = (filled(apiToken) ? 1 : 0)
                    + (filled(accountId) ? 1 : 0)
                    + (filled(zoneId) ? 1 : 0)
                    + (filled(zoneName) ? 1 : 0);
            return present > 0 && present < 4;
        }

        public String zoneNameNormalized() {
            if (zoneName == null) {
                return "";
            }
            String zone = zoneName.trim().toLowerCase();
            if (zone.endsWith(".")) {
                zone = zone.substring(0, zone.length() - 1);
            }
            return zone;
        }

        private static boolean filled(String value) {
            return value != null && !value.isBlank();
        }
    }
}
