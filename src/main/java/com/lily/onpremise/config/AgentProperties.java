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
        /** 클라우드 기동 프로브(5초 × 36)와 같은 180초 */
        @DefaultValue("180") int healthTimeoutSeconds,
        @DefaultValue("") String publicUrl,
        @DefaultValue("") String workspace,
        @DefaultValue Cloudflare cloudflare,
        @DefaultValue Burst burst,
        @DefaultValue Database database,
        /** 공개 주소의 CNAME 을 클라우드 오리진으로 바꿀 때 쓴다. 비우면 거점 전환을 거절한다 */
        @DefaultValue Cutover cutover,
        /** 사용자 레포를 이 PC 에서 빌드·실행할 때의 한도 */
        @DefaultValue Sandbox sandbox) {

    /**
     * 플랫폼 연결. 컨트롤 플레인 주소만 있고 Cloudflare 자격(API 토큰, 터널 토큰)이 없으면
     * 공개 주소는 컨트롤 플레인이 플랫폼 존에 만들어 준다.
     */
    public boolean platformExposure() {
        return controlPlaneUrl != null && !controlPlaneUrl.isBlank()
                && cloudflare != null && cloudflare.enabled()
                && !cloudflare.apiConfigured() && !cloudflare.partialApi()
                && (cloudflare.token() == null || cloudflare.token().isBlank());
    }

    /** 플랫폼 연결로 DB 터널 인증서를 받는다. 이 머신에 DB 터널 설정이 있으면 그것을 쓴다 */
    public boolean platformDatabase() {
        return controlPlaneUrl != null && !controlPlaneUrl.isBlank()
                && (database == null || !database.configured());
    }

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
     * 온프레미스가 감당하지 못하는 요청을 클라우드(k3s)로 넘긴다.
     *
     * @param builderUrl         lily-builder 의 공개 주소 (/api/burst 만 열려 있다). 예: http://builder.1.2.3.4.nip.io
     * @param token              lily-builder 의 BURST_API_TOKEN
     * @param ingressHost        넘길 곳. 클라우드 Ingress 가 듣는 IP 또는 호스트
     * @param ingressPort        보통 80
     * @param publicHost         사용자가 여는 호스트. {@code {app}} 을 앱 이름으로 바꾼다. 비우면 {@code {app}.{zone}}
     * @param localLimit         로컬 슬롯이 동시에 처리할 요청 수. 넘치는 요청이 버스팅 대상
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
            @DefaultValue("1") int warmReplicas,
            @DefaultValue("") String database) {
    }

    /**
     * 온프레미스와 클라우드 사이의 거점 전환.
     * CNAME 타입은 유지하고 내용물만 바꾼다. 값이 IP 이거나 비어 있으면 전환을 거절한다.
     *
     * @param cloudOrigin 클라우드 거점의 CNAME 내용물. 호스트 이름
     */
    public record Cutover(@DefaultValue("") String cloudOrigin) {

        public Cutover {
            if (cloudOrigin == null) {
                cloudOrigin = "";
            }
            cloudOrigin = cloudOrigin.trim().toLowerCase();
            if (cloudOrigin.endsWith(".")) {
                cloudOrigin = cloudOrigin.substring(0, cloudOrigin.length() - 1);
            }
        }

        public boolean blank() {
            return cloudOrigin.isBlank();
        }
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

    /**
     * 사용자 레포가 이 PC 를 끝까지 쓰지 못하게 하는 한도.
     * 프로세스 수는 스레드를 포함한다. JVM 앱이 수백 개까지 만들므로 기본은 1024 다.
     *
     * @param memory docker 메모리. 단위가 있어야 한다. 예: 2g
     * @param cpus   docker CPU. 1 이면 한 개
     * @param pids   프로세스 수 상한
     */
    public record Sandbox(
            @DefaultValue("2g") String memory,
            @DefaultValue("1") String cpus,
            @DefaultValue("1024") int pids) {

        public Sandbox {
            if (memory == null || !memory.matches("(?i)[1-9][0-9]{0,5}(?:k|m|g|kb|mb|gb)")) {
                throw new IllegalArgumentException("메모리 한도가 올바르지 않습니다");
            }
            memory = memory.toLowerCase();
            if (cpus == null || !cpus.matches("(?:0\\.[1-9]|[1-9](?:\\.[0-9])?|1[0-6](?:\\.0)?)")) {
                throw new IllegalArgumentException("CPU 한도가 올바르지 않습니다");
            }
            if (pids < 64 || pids > 4096) {
                throw new IllegalArgumentException("프로세스 한도가 올바르지 않습니다");
            }
        }

        /** CFS 주기 100ms 기준. 1 CPU = 100000 */
        public long cpuQuota() {
            return Math.round(Double.parseDouble(cpus) * 100_000);
        }
    }
}
