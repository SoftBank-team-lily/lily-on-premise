package com.lily.onpremise.config;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.burst.BurstClient;
import com.lily.onpremise.burst.CloudDatabase;
import com.lily.onpremise.burst.DatabaseTunnel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.expose.CloudflareHostnameProvisioner;
import com.lily.onpremise.expose.HealthProbe;
import com.lily.onpremise.expose.HttpCloudflareClient;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.pipeline.OnPremPipeline;
import com.lily.onpremise.runtime.CliContainerRuntime;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.source.GitWorkspace;
import com.lily.onpremise.source.Workspace;
import com.lily.onpremise.system.Commands;
import com.lily.onpremise.system.ProcessCommands;
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
    Readiness readiness(AgentProperties properties) {
        int timeout = Math.max(1, properties.healthTimeoutSeconds());
        return new HealthProbe(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                Duration.ofSeconds(timeout),
                Duration.ofSeconds(2));
    }

    @Bean
    CloudflareHostnameProvisioner hostnames(AgentProperties properties) {
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
        if (properties.cloudflare() == null || !properties.cloudflare().apiConfigured()) {
            return app -> exposure.publicUrl();
        }
        return app -> {
            String url = hostnames.ensureHostname(app, exposure.listenPort());
            exposure.publish(url);
            return url;
        };
    }

    /** 배포 요청에 database 가 있을 때 클라우드 RDS 를 SSH 터널로 붙인다. 설정이 없으면 DB 배포를 거절한다 */
    @Bean
    DatabaseAccess databaseAccess(AgentProperties properties, Commands commands, ObjectMapper json) {
        AgentProperties.Database db = properties.database();
        if (db == null || !db.configured() || properties.burst().builderUrl().isBlank()) {
            return DatabaseAccess.NONE;
        }
        String dir = properties.workspace();
        Path workDir = dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lily-onprem")
                : Path.of(dir);
        return new CloudDatabase(new DatabaseTunnel(db, commands, workDir), new BurstClient(properties.burst(), json));
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
            DatabaseAccess databases) {
        return new OnPremPipeline(
                workspace, analyzer, runtime, readiness, exposure, publicAddress, slots,
                properties.bluePort(), properties.greenPort(), databases);
    }
}
