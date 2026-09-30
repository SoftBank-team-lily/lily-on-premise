package com.lily.onpremise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 에이전트 한 대가 깔리는 머신에 대한 값.
 * 배포 요청마다 바뀌는 값은 {@code DeployJob} 이 가져온다.
 *
 * @param proxyPort  공개 주소가 가리키는 로컬 포트. blue/green 컨테이너 포트와 다르다
 * @param publicUrl  API 없이 named tunnel 을 쓸 때의 고정 주소
 * @param burst      클라우드 버스팅. 기본은 꺼짐
 * @param database   클라우드 RDS 연동 (SSH 터널). 배포 요청에 database 가 있을 때 쓴다
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
        @DefaultValue Cloudflare cloudflare,
        @DefaultValue Burst burst,
        @DefaultValue Database database) {

    /**
     * 온프레미스 앱이 클라우드와 같은 RDS 를 쓰도록 SSH 포트포워딩 터널을 연다.
     * 배스천 계정은 RDS 포트로의 포워딩만 허용된다 (셸 없음). DB 계정·비밀번호는 lily-builder 를 거쳐 받는다.
     *
     * @param sshHost     배스천 (lily-server 공개 IP)
     * @param sshUser     포워딩 전용 계정
     * @param sshKey      개인키 파일 경로 (권한 600)
     * @param remoteHost  RDS 엔드포인트
     * @param bindHost    터널을 여는 로컬 주소. 컨테이너에서만 닿도록 Docker 브리지 게이트웨이(172.17.0.1)
     */
    public record Database(
            @DefaultValue("") String sshHost,
            @DefaultValue("lily-tunnel") String sshUser,
            @DefaultValue("") String sshKey,
            @DefaultValue("") String remoteHost,
            @DefaultValue("5432") int remotePort,
            @DefaultValue("172.17.0.1") String bindHost,
            @DefaultValue("15432") int bindPort) {

        public boolean configured() {
            return !sshHost.isBlank() && !sshKey.isBlank() && !remoteHost.isBlank();
        }
    }

    /**
     * 온프레미스가 감당하지 못하는 연결을 클라우드(k3s)로 넘긴다.
     *
     * @param builderUrl         lily-builder 의 공개 주소 (/api/burst 만 열려 있다). 예: http://builder.1.2.3.4.nip.io
     * @param token              lily-builder 의 BURST_API_TOKEN
     * @param ingressHost        넘길 곳. 클라우드 Ingress 가 듣는 IP 또는 호스트
     * @param ingressPort        보통 80
     * @param publicHost         사용자가 여는 호스트. {@code {app}} 을 앱 이름으로 바꾼다. 비우면 {@code {app}.{zone}}
     * @param localLimit         로컬 슬롯이 동시에 받을 연결 수. 넘치는 연결이 버스팅 대상
     * @param scaleUpAfterSeconds 이 시간 동안 계속 넘치면 클라우드를 올린다
     * @param cooldownSeconds    이 시간 동안 한가하면 클라우드를 내린다
     * @param replicas           클라우드에 올릴 Pod 수 (DB 커넥션 제한: 로컬 1 + 클라우드 N, 각 풀 3 이 20 이하)
     * @param database           클라우드 대기 배포에 붙일 DB (postgres / mysql). 비우면 없음
     */
    public record Burst(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String builderUrl,
            @DefaultValue("") String token,
            @DefaultValue("") String ingressHost,
            @DefaultValue("80") int ingressPort,
            @DefaultValue("") String publicHost,
            @DefaultValue("8") int localLimit,
            @DefaultValue("3") int scaleUpAfterSeconds,
            @DefaultValue("30") int cooldownSeconds,
            @DefaultValue("2") int replicas,
            @DefaultValue("") String database) {
    }

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
