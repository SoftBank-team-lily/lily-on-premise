package com.lily.onpremise.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContainerStatsTest {

    @Test
    void docker_stats_한_줄을_읽는다() {
        ContainerStats.Usage usage = ContainerStats.parse("blog-green", "12.34%|1.5GiB / 7.66GiB|19.58%");

        assertThat(usage.cpuPercent()).isEqualTo(12.34);
        assertThat(usage.memoryMiB()).isCloseTo(1536, within(0.01));
        assertThat(usage.memoryPercent()).isEqualTo(19.58);
    }

    @Test
    void 단위를_MiB_로_맞추고_모르는_형식은_버린다() {
        assertThat(ContainerStats.mebibytes("512KiB")).isEqualTo(0.5);
        assertThat(ContainerStats.mebibytes("200MB")).isEqualTo(200);
        assertThat(ContainerStats.parse("x", "--")).isNull();
        assertThat(ContainerStats.parse("x", "n/a|n/a|n/a")).isNull();
    }
}
