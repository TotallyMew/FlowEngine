package dev.flowengine.execution;

import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.GraphValidator;
import dev.flowengine.workflow.WorkflowGraph;
import dev.flowengine.workflow.WorkflowGraph.Edge;
import dev.flowengine.workflow.WorkflowGraph.Node;
import dev.flowengine.workflow.WorkflowGraph.Position;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.flowengine.execution.ExecutionEngine.NodeStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEngineTest {
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void mergesJsonAndKeepsSiblingInputsIndependent() {
        var registry = new NodeRegistry();
        var engine = new ExecutionEngine(new GraphValidator(registry), registry, clock);
        var values = new LinkedHashMap<String, Object>();
        values.put("count", 2);
        values.put("missing", null);
        var graph = new WorkflowGraph(
                List.of(node("start", "MANUAL_TRIGGER", Map.of()),
                        node("first", "SET_DATA", Map.of("values", Map.of("count", 1, "keep", true))),
                        node("second", "SET_DATA", Map.of("values", values)),
                        node("sibling", "LOG", Map.of("message", "Side branch"))),
                List.of(new Edge("e1", "start", "first"), new Edge("e2", "first", "second"),
                        new Edge("e3", "first", "sibling")));

        Map<String, Object> input = Map.of("sku", "BIKE-123", "count", 0);
        var outcome = engine.run(graph, input, now.plusSeconds(30));

        assertThat(outcome.error()).isNull();
        assertThat(outcome.results()).extracting(ExecutionEngine.NodeResult::status).containsOnly(COMPLETED);
        assertThat(outcome.results().getFirst().input()).isEqualTo(input);
        assertThat(outcome.results().getFirst().output()).isEqualTo(input);
        assertThat(outcome.results().get(2).output()).containsEntry("count", 2).containsEntry("missing", null).containsEntry("keep", true);
        assertThat(outcome.results().get(3).output()).isEqualTo(Map.of("sku", "BIKE-123", "count", 1, "keep", true));
        assertThat(outcome.results().get(3).message()).isEqualTo("Side branch");
    }

    @Test
    void timesOutBeforeExecutingAnyNodeAndSkipsRemainingNodes() {
        var registry = spy(new NodeRegistry());
        var outcome = new ExecutionEngine(new GraphValidator(registry), registry, clock).run(simpleGraph(), Map.of(), now);

        assertThat(outcome.error()).isEqualTo("Execution timed out.");
        assertThat(outcome.results()).extracting(ExecutionEngine.NodeResult::status).containsExactly(FAILED, SKIPPED);
        verify(registry, never()).execute(anyString(), anyMap(), anyMap());
    }

    @Test
    void sanitizesUnexpectedNodeFailures() {
        var registry = spy(new NodeRegistry());
        doThrow(new IllegalStateException("internal detail")).when(registry).execute(eq("LOG"), anyMap(), anyMap());
        var outcome = new ExecutionEngine(new GraphValidator(registry), registry, clock)
                .run(simpleGraph(), Map.of(), now.plusSeconds(30));

        assertThat(outcome.results()).extracting(ExecutionEngine.NodeResult::status).containsExactly(COMPLETED, FAILED);
        assertThat(outcome.error()).isEqualTo("Node execution failed.");
    }

    private WorkflowGraph simpleGraph() {
        return new WorkflowGraph(List.of(node("start", "MANUAL_TRIGGER", Map.of()), node("log", "LOG", Map.of("message", "OK"))),
                List.of(new Edge("e", "start", "log")));
    }

    private Node node(String id, String type, Map<String, Object> config) {
        return new Node(id, type, id, new Position(0, 0), config);
    }
}
