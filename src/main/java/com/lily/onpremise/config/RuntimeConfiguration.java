package com.lily.onpremise.config;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.burst.BurstClient;
import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.burst.CloudDatabase;
import com.lily.onpremise.burst.DatabaseTunnel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.expose.CandidateJudge;
import com.lily.onpremise.expose.CloudflareHostnameProvisioner;
import com.lily.onpremise.expose.HealthProbe;
import com.lily.onpremise.expose.LoopbackCandidateJudge;
import com.lily.onpremise.expose.HttpCloudflareClient;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.pipeline.OnPremPipeline;
import com.lily.onpremise.platform.PlatformChannel;
import com.lily.onpremise.platform.PlatformDatabase;
import com.lily.onpremise.platform.RelayCloudflareClient;
import com.lily.onpremise.schema.FlywaySchemaApply;
import com.lily.onpremise.schema.SchemaApply;
import com.lily.onpremise.runtime.CliContainerRuntime;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.source.GitWorkspace;
import com.lily.onpremise.source.Workspace;
import com.lily.onpremise.system.Commands;
import com.lily.onpremise.system.ProcessCommands;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class RuntimeConfiguration {

    @Bean(destroyMethod = "close")
    Commands commands() {
        return new ProcessCommands();
    }

    @Bean
    Workspace workspace(Commands commands, AgentProperties properties) {
        String dir = properties.workspace();
        Path root = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lily-onprem")
                : Path.of(dir);
        return new GitWorkspace(commands, root);
    }

    @Bean
    ContainerRuntime containerRuntime(Commands commands) {
        return new CliContainerRuntime(commands);
    }

    @Bean
    SchemaApply schemaApply() {
        return new FlywaySchemaApply();
    }

    @Bean
    CandidateJudge candidateJudge() {
        return new LoopbackCandidateJudge(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    @Bean
    Readiness readiness(AgentProperties properties) {
        int timeout = Math.max(1, properties.healthTimeoutSeconds());
        return new HealthProbe(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Duration.ofSeconds(timeout),
                Duration.ofSeconds(2));
    }

    /** 플랫폼 연결이면 Cloudflare 호출을 컨트롤 플레인이 대신 한다. 이 머신에는 API 토큰이 없다 */
    @Bean
    CloudflareHostnameProvisioner hostnames(AgentProperties properties, PlatformChannel channel) {
        if (properties.platformExposure()) {
            return new CloudflareHostnameProvisioner(new RelayCloudflareClient(channel), properties.cloudflare());
        }
        String apiToken = properties.cloudflare() == null ? "" : properties.cloudflare().apiToken();
        return new CloudflareHostnameProvisioner(new HttpCloudflareClient(apiToken), properties.cloudflare());
    }

    @Bean(destroyMethod = "close")
    LocalExposure exposure(
            AgentProperties properties,
            Commands commands,
            AgentIdentity identity,
            CloudflareHostnameProvisioner hostnames) {
        return new LocalExposure(properties, commands, identity, hostnames);
    }

    @Bean
    PublicAddress publicAddress(
            AgentProperties properties, LocalExposure exposure, CloudflareHostnameProvisioner hostnames) {
        boolean api = properties.cloudflare() != null && properties.cloudflare().apiConfigured();
        if (!api && !properties.platformExposure()) {
            return app -> exposure.publicUrl();
        }
        return app -> {
            // 플랫폼 존을 받지 못해 quick tunnel 로 열렸으면 그 주소를 쓴다
            if (!api && !hostnames.configured()) {
                return exposure.publicUrl();
            }
            String url = hostnames.ensureHostname(app, exposure.listenPort());
            exposure.publish(url);
            return url;
        };
    }

    /**
     * 배포 요청에 database 가 있을 때 클라우드 RDS 를 SSH 터널로 붙인다.
     * 이 머신에 터널 설정이 있으면 그것을, 없고 컨트롤 플레인에 붙으면 플랫폼이 주는 인증서를 쓴다. 둘 다 아니면 DB 배포를 거절한다
     */
    @Bean
    DatabaseAccess databaseAccess(AgentProperties properties, Commands commands, ObjectMapper json) {
        AgentProperties.Database db = properties.database();
        String dir = properties.workspace();
        Path workDir = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lily-onprem")
                : Path.of(dir);
        if (properties.platformDatabase()) {
            return new PlatformDatabase(commands, db, workDir);
        }
        if (db == null || !db.configured() || properties.burst().builderUrl().isBlank()) {
            return DatabaseAccess.NONE;
        }
        return new CloudDatabase(new DatabaseTunnel(db, commands, workDir), new BurstClient(properties.burst(), json));
    }

    @Bean(destroyMethod = "close")
    HomeCutover homeCutover(
            AgentProperties properties,
            CloudflareHostnameProvisioner hostnames,
            CloudBurst burst,
            ObjectProvider<OnPremPipeline> pipeline,
            SlotBook slots,
            ContainerRuntime runtime,
            Commands commands,
            ObjectMapper json) {
        String dir = properties.workspace();
        Path root = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lily-onprem")
                : Path.of(dir);
        return new HomeCutover(properties, hostnames, burst, pipeline, slots, runtime, commands, json, root);
    }

    @Bean
    OnPremPipeline pipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            LocalExposure exposure,
            PublicAddress publicAddress,
            SlotBook slots,
            AgentProperties properties,
            DatabaseAccess databases,
            SchemaApply schema,
            CandidateJudge judge,
            HomeCutover homes) {
        return new OnPremPipeline(
                workspace, analyzer, runtime, readiness, exposure, publicAddress, slots,
                properties.bluePort(), properties.greenPort(), databases, schema, judge, homes);
    }
}
