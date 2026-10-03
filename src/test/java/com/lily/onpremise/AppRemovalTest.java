package com.lily.onpremise;

import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.database.DatabaseModes;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.Slot;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppRemovalTest {

    private final HomeCutover cutover = mock(HomeCutover.class);
    private final CloudBurst burst = mock(CloudBurst.class);
    private final TrafficSwitch traffic = mock(TrafficSwitch.class);
    private final SlotBook slots = new SlotBook();
    private final ContainerRuntime runtime = mock(ContainerRuntime.class);
    private final Commands commands = mock(Commands.class);
    private final DatabaseModes databases = mock(DatabaseModes.class);
    private final AppRemoval removal = new AppRemoval(cutover, burst, traffic, slots, runtime, commands, databases);

    @Test
    void 트래픽을_받던_앱을_지우면_프록시를_비우고_두_슬롯_컨테이너와_이미지를_지운다() {
        slots.commit("blog", Slot.BLUE);
        when(traffic.upstreamPort()).thenReturn(18080);
        when(commands.output(List.of("docker", "images", "-q", "lily-onprem/blog"))).thenReturn("aaa\nbbb\naaa\n");

        List<String> removed = removal.remove("blog", false);

        verify(cutover).forget("blog");
        verify(burst).forget("blog");
        verify(traffic).route(0);
        verify(runtime).stop("blog-blue");
        verify(runtime).stop("blog-green");
        verify(commands).run(List.of("docker", "rmi", "-f", "aaa", "bbb"), null);
        verify(databases, never()).dropLocal(anyString());
        assertThat(slots.active("blog")).isEmpty();
        assertThat(removed).containsExactly("proxy", "containers", "images 2");
    }

    @Test
    void database를_주면_이_PC의_앱_DB도_지운다() {
        when(commands.output(any())).thenReturn("");
        when(databases.dropLocal("blog")).thenReturn(List.of("postgres blog"));

        List<String> removed = removal.remove("blog", true);

        verify(databases).dropLocal("blog");
        assertThat(removed).contains("database postgres blog");
    }

    @Test
    void 다른_앱이_트래픽을_받고_있으면_프록시는_그대로_둔다() {
        slots.commit("shop", Slot.GREEN);
        when(traffic.upstreamPort()).thenReturn(18081);
        when(commands.output(any())).thenReturn("");

        removal.remove("blog", false);

        verify(traffic, never()).route(anyInt());
        assertThat(slots.active("shop")).isPresent();
    }

    @Test
    void 거점을_옮기는_중이면_컨테이너를_지우지_않고_실패한다() {
        doThrow(new IllegalStateException("거점을 옮기는 중이라 지울 수 없습니다")).when(cutover).forget("blog");

        assertThatThrownBy(() -> removal.remove("blog", true)).hasMessageContaining("거점을 옮기는 중");

        verify(runtime, never()).stop(anyString());
        verify(databases, never()).dropLocal(anyString());
    }
}
