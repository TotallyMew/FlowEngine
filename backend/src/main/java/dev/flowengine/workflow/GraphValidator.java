package dev.flowengine.workflow;

import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.WorkflowGraph.Edge;
import dev.flowengine.workflow.WorkflowGraph.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GraphValidator {
    public record Plan(List<Node> order, Map<String, Edge> incoming) {}

    private final NodeRegistry registry;

    public GraphValidator(NodeRegistry registry) {
        this.registry = registry;
    }

    public Plan validate(WorkflowGraph graph, boolean runnable) {
        var nodes = new LinkedHashMap<String, Node>();
        int triggers = 0;
        for (var node : graph.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw new GraphException("Duplicate node ID: " + node.id());
            }
            if (!Double.isFinite(node.position().x()) || !Double.isFinite(node.position().y())
                    || Math.abs(node.position().x()) > 100_000 || Math.abs(node.position().y()) > 100_000) {
                throw new GraphException("Node position is outside the editor bounds.");
            }
            if (!registry.definition(node.type()).input()) triggers++;
            try {
                registry.validate(node.type(), node.config());
            } catch (GraphException ex) {
                throw new GraphException(node.label() + ": " + ex.getMessage());
            }
        }
        if (triggers > 1 || (runnable && triggers != 1)) {
            throw new GraphException("A workflow must have exactly one Manual Trigger to run.");
        }

        var edgeIds = new HashSet<String>();
        var incoming = new HashMap<String, Edge>();
        var children = new HashMap<String, List<String>>();
        for (var edge : graph.edges()) {
            if (!edgeIds.add(edge.id())) throw new GraphException("Duplicate connection ID: " + edge.id());
            var source = nodes.get(edge.source());
            var target = nodes.get(edge.target());
            if (source == null || target == null) {
                throw new GraphException("Every connection must reference two existing nodes.");
            }
            if (!registry.definition(source.type()).outputPorts().contains(edge.sourcePort())
                    || !registry.definition(target.type()).input()) {
                throw new GraphException("Connection uses an unavailable port.");
            }
            if (incoming.putIfAbsent(target.id(), edge) != null) {
                throw new GraphException(target.label() + " already has an incoming connection.");
            }
            children.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(target.id());
        }

        var ready = new ArrayDeque<String>();
        for (var node : nodes.values()) {
            if (!incoming.containsKey(node.id())) ready.addLast(node.id());
        }
        if (runnable && ready.size() != 1) {
            throw new GraphException("Connect all nodes to the Manual Trigger before running.");
        }
        var order = new ArrayList<Node>();
        while (!ready.isEmpty()) {
            var id = ready.removeFirst();
            order.add(nodes.get(id));
            ready.addAll(children.getOrDefault(id, List.of()));
        }
        if (order.size() != nodes.size()) throw new GraphException("Cycles are unsupported.");
        return new Plan(List.copyOf(order), Map.copyOf(incoming));
    }
}
