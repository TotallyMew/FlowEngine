package dev.flowengine.workflow;

import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.WorkflowGraph.Edge;
import dev.flowengine.workflow.WorkflowGraph.Node;
import dev.flowengine.workflow.WorkflowGraph.Position;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GraphValidatorTest {
    private final GraphValidator validator = new GraphValidator(new NodeRegistry());

    @Test
    void plansFanOutInDependencyOrderEvenWhenNodesAreStoredOutOfOrder() {
        var graph = new WorkflowGraph(List.of(log("left"), log("right"), trigger()),
                List.of(new Edge("e1", "start", "left"), new Edge("e2", "start", "right")));
        var plan = validator.validate(graph, true);

        assertThat(plan.order()).extracting(Node::id).containsExactly("start", "left", "right");
        assertThat(plan.parents()).containsEntry("left", "start").containsEntry("right", "start");
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
