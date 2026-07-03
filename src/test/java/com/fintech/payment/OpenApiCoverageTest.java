package com.fintech.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 — OpenAPI SpringDoc path coverage smoke test (§12.6.1 item 8).
 *
 * <p>Asserts that the FR-mapped controller paths are present in the
 * generated OpenAPI document at {@code /v3/api-docs}. This is the
 * cheapest way to catch:</p>
 * <ul>
 *   <li>Path typos on {@code @RequestMapping} / {@code @GetMapping} etc.</li>
 *   <li>Missing controller registration when a new FR is added.</li>
 *   <li>Spring Boot 3.x + SpringDoc 2.x compatibility regressions.</li>
 * </ul>
 *
 * <p>Path-list curates against the SRS FR-sections. Drift is logged in
 * §12.6 — if the FR-list grows, this assertion grows alongside.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Disable scheduled tasks that would fire on context-startup.
        "spring.scheduling.enabled=false"
})
class OpenApiCoverageTest {

    /** FR-anchored paths that MUST appear in {@code /v3/api-docs}. */
    private static final Set<String> REQUIRED_PATHS = Set.of(
            "/api/v1/accounts",            // FR-1.1 create + FR-1.3 list
            "/api/v1/accounts/{id}",       // FR-1.2 get
            "/api/v1/accounts/{id}/status",// FR-1.4 / FR-1.5
            "/api/v1/payments",            // FR-2.1 submit + FR-2.5 list
            "/api/v1/payments/{id}",       // FR-2.2 get
            "/api/v1/payments/{id}/reverse", // FR-2.6 reverse
            "/api/v1/settlements",         // FR-3.5 list
            "/api/v1/settlements/{id}",    // FR-3.5 single
            "/api/v1/settlements/{id}/process", // FR-3.2 manual trigger
            "/api/v1/audit",               // FR-4.1 / FR-4.2
            "/api/v1/reports/daily"        // FR-4.3
    );

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("OpenAPI document lists every FR-anchored controller path")
    void every_known_fr_path_is_present_in_open_api_doc() throws Exception {
        // Pull /v3/api-docs and parse the JSON Path-objects.
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port).build();
        String body = client.get().uri("/v3/api-docs").retrieve().body(String.class);
        assertThat(body).as("OpenAPI doc is reachable").isNotBlank();

        JsonNode doc = objectMapper.readTree(body);
        JsonNode pathsNode = doc.get("paths");
        assertThat(pathsNode).as("/v3/api-docs has a 'paths' object").isNotNull();

        Set<String> present = new HashSet<>();
        pathsNode.fieldNames().forEachRemaining(present::add);

        // Every FR-anchored path must be present.
        for (String requiredPath : REQUIRED_PATHS) {
            assertThat(present)
                    .as("OpenAPI doc missing required path: " + requiredPath)
                    .contains(requiredPath);
        }

        // Sanity: we don't have a surprise excess (warn-only, not hard-fail).
        // If you want strict matching, uncomment the next line.
        // assertThat(present).hasSize(REQUIRED_PATHS.size());
    }
}
