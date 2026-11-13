package dev.flowengine.workflow;

import dev.flowengine.node.GitHubCreateIssueNode;
import dev.flowengine.node.HttpRequestNode;
import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.WorkflowGraph.Edge;
import dev.flowengine.workflow.WorkflowGraph.Node;
import dev.flowengine.workflow.WorkflowGraph.Position;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphValidatorTest {
    private final GraphValidator validator;

    GraphValidatorTest() {
        var http = mock(HttpRequestNode.class);
        when(http.definition()).thenCallRealMethod();
        var github = mock(GitHubCreateIssueNode.class);
        when(github.definition()).thenCallRealMethod();
        validator = new GraphValidator(new NodeRegistry(http, github));
    }

    @Test
    void plansFanOutInDependencyOrderEvenWhenNodesAreStoredOutOfOrder() {
        var graph = new WorkflowGraph(List.of(log("left"), log("right"), trigger()),
                List.of(new Edge("e1", "start", "left"), new Edge("e2", "start", "right")));
        var plan = validator.validate(graph, true);

        assertThat(plan.order()).extracting(Node::id).containsExactly("start", "left", "right");
        assertThat(plan.incoming()).containsEntry("left", new Edge("e1", "start", "left"))
                .containsEntry("right", new Edge("e2", "start", "right"));
    }

    @Test
    void validatesNamedOutputPortsAndKeepsLegacyEdges() {
        var condition = new Node("check", "CONDITION", "Check SKU", new Position(100, 0),
                Map.of("field", "sku", "operator", "IS_EMPTY"));
        var nodes = List.of(trigger(), condition, log("review"), log("ready"));
        var legacy = new Edge("e1", "start", "check", null);
        var matched = new Edge("e2", "check", "review", "true");
        var unmatched = new Edge("e3", "check", "ready", "false");
        var plan = validator.validate(new WorkflowGraph(nodes, List.of(legacy, matched, unmatched)), true);
        assertThat(legacy.sourcePort()).isEqualTo("out");
        assertThat(plan.incoming()).containsEntry("review", matched).containsEntry("ready", unmatched);

        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(nodes,
                List.of(legacy, new Edge("bad", "check", "review"))), false)).hasMessageContaining("unavailable port");
        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(nodes,
                List.of(new Edge("bad", "start", "check", "false"))), false)).hasMessageContaining("unavailable port");
    }

    @Test
    void allowsDisconnectedDraftButRejectsRunningIt() {
        var graph = new WorkflowGraph(List.of(trigger(), log("log")), List.of());
        assertThatCode(() -> validator.validate(graph, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(graph, true)).hasMessageContaining("Connect all nodes");
        assertThatThrownBy(() -> validator.validate(WorkflowGraph.empty(), true)).hasMessageContaining("exactly one");
    }

    @Test
    void rejectsCyclesEvenInDisconnectedComponents() {
        var graph = new WorkflowGraph(List.of(trigger(), log("a"), log("b")),
                List.of(new Edge("e1", "a", "b"), new Edge("e2", "b", "a")));
        assertThatThrownBy(() -> validator.validate(graph, true)).hasMessageContaining("Cycles");
    }

    @Test
    void rejectsInvalidPortsMissingNodesAndMultipleParents() {
        var nodes = List.of(trigger(), log("a"), log("b"));
        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(nodes, List.of(new Edge("e", "a", "start"))), false))
                .hasMessageContaining("unavailable port");
        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(nodes, List.of(new Edge("e", "a", "missing"))), false))
                .hasMessageContaining("existing nodes");
        var merge = new WorkflowGraph(nodes, List.of(new Edge("e1", "start", "b"), new Edge("e2", "a", "b")));
        assertThatThrownBy(() -> validator.validate(merge, false)).hasMessageContaining("incoming connection");
    }

    @Test
    void rejectsDuplicateNodeIdsAndBadConfiguration() {
        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(List.of(log("a"), log("a")), List.of()), false))
                .hasMessageContaining("Duplicate node ID");
        var bad = new Node("bad", "SET_DATA", "Set Data", new Position(0, 0), Map.of("values", List.of(1)));
        assertThatThrownBy(() -> validator.validate(new WorkflowGraph(List.of(bad), List.of()), false))
                .hasMessageContaining("requires a JSON object");
    }

    private Node trigger() {
        return new Node("start", "MANUAL_TRIGGER", "Start", new Position(0, 0), Map.of());
    }

    private Node log(String id) {
        return new Node(id, "LOG", id, new Position(100, 0), Map.of("message", "Recorded"));
    }
}
