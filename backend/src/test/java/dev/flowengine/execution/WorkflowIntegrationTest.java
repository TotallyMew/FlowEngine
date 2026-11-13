package dev.flowengine.execution;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class WorkflowIntegrationTest {
    private static final String WORKFLOWS = "/api/workflows";
    private static final String PASSWORD = "integration-operator-password";
    private static final String GRAPH = """
            {"nodes":[
                {"id":"start","type":"MANUAL_TRIGGER","label":"Start","position":{"x":40,"y":60},"config":{}},
                {"id":"set","type":"SET_DATA","label":"Set Data","position":{"x":250,"y":60},"config":{"values":{"project":"flowengine"}}},
                {"id":"log","type":"LOG","label":"Log","position":{"x":450,"y":60},"config":{"message":"workflow verified"}}
            ],"edges":[{"id":"e1","source":"start","target":"set"},{"id":"e2","source":"set","target":"log"}]}
            """;

    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
    @Container static final RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.rabbitmq.host", rabbit::getHost);
        properties.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        properties.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        properties.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        properties.add("flow.operator.password", () -> PASSWORD);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired RabbitTemplate broker;
    @Autowired ExecutionService runs;
    @Autowired TransactionTemplate transaction;

    @Test
    void savesLoadsExecutesAndRetainsTheRunSnapshotAfterEditing() throws Exception {
        String workflow = create("Verified workflow");
        mvc.perform(post(workflow + "/executions").with(user("alice")).with(csrf())
                .contentType("application/json").content("{\"revision\":0}")).andExpect(status().isUnprocessableContent());

        long revision = save(workflow, 0);
        mvc.perform(get(workflow).with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.nodes[1].position.x").value(250))
                .andExpect(jsonPath("$.graph.nodes[1].config.values.project").value("flowengine"));
        mvc.perform(put(workflow).with(user("alice")).with(csrf()).contentType("application/json")
                .content(updateBody(0, "Stale"))).andExpect(status().isConflict());

        String executions = workflow + "/executions";
        var queued = body(mvc.perform(post(executions).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"revision\":" + revision + "}")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn().getResponse().getContentAsString());
        String id = queued.path("id").asText();
        save(workflow, revision);

        await().atMost(15, SECONDS).untilAsserted(() -> mvc.perform(get(executions + "/" + id).with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.workflowRevision").value(revision))
                .andExpect(jsonPath("$.input").isEmpty())
                .andExpect(jsonPath("$.results[0].output").isEmpty())
                .andExpect(jsonPath("$.results[2].input.project").value("flowengine"))
                .andExpect(jsonPath("$.results[2].output.project").value("flowengine"))
                .andExpect(jsonPath("$.results[2].message").value("workflow verified"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty()).andExpect(jsonPath("$.finishedAt").isNotEmpty()));

        broker.convertAndSend("flowengine.executions.v1", id);
        assertThat(runs.claim(UUID.fromString(id))).isEmpty();
        mvc.perform(get(executions).with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].status").value("COMPLETED"));
        mvc.perform(get(executions + "/" + id).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(get(create("Another workflow") + "/executions/" + id).with(user("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void runsTheSameRevisionWithDifferentInputsAndRetainsEachPayload() throws Exception {
        String workflow = create("Per-run inputs");
        long revision = save(workflow, 0);
        String executions = workflow + "/executions";

        mvc.perform(post(executions).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"revision\":" + revision + ",\"input\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(executions).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"revision\":" + revision + ",\"input\":{\"sku\":\"" + "x".repeat(128 * 1024) + "\"}}"))
                .andExpect(status().isContentTooLarge());
        mvc.perform(get(executions).with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        String first = queueWithSku(executions, revision, "BIKE-123");
        String second = queueWithSku(executions, revision, "BIKE-456");
        assertThat(first).isNotEqualTo(second);

        await().atMost(15, SECONDS).untilAsserted(() -> {
            assertRunInput(executions + "/" + second, revision, "BIKE-456");
            assertRunInput(executions + "/" + first, revision, "BIKE-123");
        });
        mvc.perform(get(workflow).with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.graph.nodes[0].config").isEmpty());
        mvc.perform(get(executions + "/" + first).with(user("other"))).andExpect(status().isNotFound());
    }

    @Test
    void failsAnAbandonedClaimWithoutMakingItRunnableAgain() throws Exception {
        String workflow = create("Recovery");
        long revision = save(workflow, 0);
        var workflowId = UUID.fromString(workflow.substring(workflow.lastIndexOf('/') + 1));
        UUID id = transaction.execute(status -> {
            UUID runId = runs.request(workflowId, "alice", revision, Map.of()).id();
            assertThat(runs.claim(runId)).isPresent();
            // Commit an abandoned claim without exposing the pending row to the dispatcher.
            jdbc.sql("""
                    UPDATE executions SET deadline_at = now() - interval '30 seconds'
                    WHERE id = :id
                    """).param("id", runId).update();
            return runId;
        });

        await().atMost(10, SECONDS).untilAsserted(() -> {
            runs.expire();
            var detail = runs.get(workflowId, id, "alice");
            assertThat(detail.status()).isEqualTo("FAILED");
            assertThat(detail.finishedAt()).isBeforeOrEqualTo(Instant.now());
            assertThat(detail.error()).contains("deadline");
        });
        assertThat(runs.claim(id)).isEmpty();
    }

    @Test
    void enforcesLoginCsrfAndOwnershipAtTheApiBoundary() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get(WORKFLOWS)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/session/login").param("username", "operator").param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/session/login").with(csrf()).param("username", "operator").param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        var signedIn = mvc.perform(post("/api/session/login").with(csrf()).param("username", "operator").param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/session").session((MockHttpSession) signedIn))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("operator"));
        mvc.perform(post(WORKFLOWS).with(user("alice")).contentType("application/json").content("{\"name\":\"Invalid\"}"))
                .andExpect(status().isForbidden());

        String workflow = create("Private workflow");
        mvc.perform(get(workflow).with(user("other"))).andExpect(status().isNotFound());
        mvc.perform(put(workflow).with(user("other")).with(csrf()).contentType("application/json")
                .content(updateBody(0, "Other"))).andExpect(status().isNotFound());
        mvc.perform(delete(workflow).with(user("other")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(workflow + "/duplicate").with(user("other")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(workflow + "/duplicate").with(user("alice")).with(csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Private workflow (copy)"));
        mvc.perform(post(WORKFLOWS).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"name\":\"" + "x".repeat(128 * 1024) + "\"}")).andExpect(status().isContentTooLarge());
        mvc.perform(delete(workflow).with(user("alice")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(workflow).with(user("alice"))).andExpect(status().isNotFound());
    }

    private String create(String name) throws Exception {
        var created = mvc.perform(post(WORKFLOWS).with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()).andReturn();
        return WORKFLOWS + "/" + body(created.getResponse().getContentAsString()).path("id").asText();
    }

    private String queueWithSku(String executions, long revision, String sku) throws Exception {
        var payload = json.writeValueAsString(Map.of("revision", revision,
                "input", Map.of("sku", sku, "metadata", Map.of("tags", java.util.Arrays.asList("new", null)))));
        var queued = mvc.perform(post(executions).with(user("alice")).with(csrf())
                .contentType("application/json").content(payload)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.input.sku").value(sku)).andReturn();
        return body(queued.getResponse().getContentAsString()).path("id").asText();
    }

    private void assertRunInput(String execution, long revision, String sku) throws Exception {
        var result = mvc.perform(get(execution).with(user("alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.workflowRevision").value(revision))
                .andExpect(jsonPath("$.input.sku").value(sku))
                .andExpect(jsonPath("$.results[0].input.sku").value(sku))
                .andExpect(jsonPath("$.results[0].output.sku").value(sku))
                .andExpect(jsonPath("$.results[2].output.sku").value(sku))
                .andExpect(jsonPath("$.results[2].output.project").value("flowengine"))
                .andReturn();
        assertThat(body(result.getResponse().getContentAsString())
                .at("/results/2/output/metadata/tags/1").isNull()).isTrue();
    }

    private long save(String workflow, long revision) throws Exception {
        var saved = mvc.perform(put(workflow).with(user("alice")).with(csrf()).contentType("application/json")
                .content(updateBody(revision, "Saved workflow"))).andExpect(status().isOk()).andReturn();
        return body(saved.getResponse().getContentAsString()).path("revision").asLong();
    }

    private String updateBody(long revision, String name) {
        return "{\"name\":\"" + name + "\",\"revision\":" + revision + ",\"graph\":" + GRAPH + "}";
    }

    private JsonNode body(String body) {
        return json.readTree(body);
    }
}
