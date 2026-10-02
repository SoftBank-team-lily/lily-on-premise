package com.lily.onpremise.remediate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalIncidentTest {

    @Test
    void javaFrameBecomesARepoPathAndMasksTheToken() {
        LocalIncident.Incident incident = LocalIncident.from("blog", List.of(
                "java.lang.IllegalStateException: token=ghp_abcdefghij1234567890",
                "\tat com.acme.OrderService.label(OrderService.java:42)",
                "\tat org.springframework.web.Foo.bar(Foo.java:10)")).orElseThrow();

        assertThat(incident.signature()).isEqualTo("IllegalStateException OrderService.java:42");
        assertThat(incident.files()).containsExactly("src/main/java/com/acme/OrderService.java");
        assertThat(incident.log()).contains("token=***").doesNotContain("ghp_");
    }

    @Test
    void nodeAndPythonKeepOnlyTheAppWorkdir() {
        LocalIncident.Incident node = LocalIncident.from("blog", List.of(
                "TypeError: bad",
                "    at Object.<anonymous> (/app/src/index.js:10:5)",
                "    at Module._compile (node:internal/modules/cjs/loader:1364:14)")).orElseThrow();
        assertThat(node.files()).containsExactly("src/index.js");
        assertThat(node.signature()).isEqualTo("TypeError index.js:10");

        LocalIncident.Incident python = LocalIncident.from("blog", List.of(
                "Traceback (most recent call last):",
                "  File \"/app/main.py\", line 12, in <module>",
                "NameError: name 'x' is not defined",
                "  File \"/usr/lib/python3.12/site-packages/flask/app.py\", line 3")).orElseThrow();
        assertThat(python.files()).containsExactly("main.py");
        assertThat(python.signature()).startsWith("NameError");
    }

    @Test
    void unknownAbsolutePathStaysOnTheMachine() {
        assertThat(LocalIncident.from("blog", List.of(
                "Error: bad",
                "    at main (/opt/app/server.js:4:1)"))).isEmpty();
        assertThat(LocalIncident.from("blog", List.of(
                "java.lang.NullPointerException",
                "\tat org.springframework.web.Foo.bar(Foo.java:10)"))).isEmpty();
    }
}
