package dev.flowengine.execution;

import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.GraphValidator;
import dev.flowengine.workflow.WorkflowGraph;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ExecutionEngine {
    public enum NodeStatus { COMPLETED, FAILED, SKIPPED }

    public record NodeResult(String nodeId, NodeStatus status, Instant startedAt, Instant finishedAt,
                             Map<String, Object> input, Map<String, Object> output, String message, String error) {}

    public record Outcome(List<NodeResult> results, String error) {}

    private final GraphValidator validator;
    private final NodeRegistry registry;
    private final Clock clock;

    @Autowired
    public ExecutionEngine(GraphValidator validator, NodeRegistry registry) {
        this(validator, registry, Clock.systemUTC());
    }

    ExecutionEngine(GraphValidator validator, NodeRegistry registry, Clock clock) {
        this.validator = validator;
        this.registry = registry;
        this.clock = clock;
    }

    public Outcome run(WorkflowGraph graph, Map<String, Object> runInput, Instant deadline) {
        var plan = validator.validate(graph, true);
        var outputs = new HashMap<String, Map<String, Object>>();
        var results = new ArrayList<NodeResult>();
        String error = null;
        for (var node : plan.order()) {
            if (error != null) {
                results.add(new NodeResult(node.id(), NodeStatus.SKIPPED, null, null, null, null, null, null));
                continue;
            }

            var parent = plan.parents().get(node.id());
            Map<String, Object> input = parent == null ? runInput : outputs.get(parent);
            var start = clock.instant();
            try {
                checkDeadline(deadline);
                var output = registry.execute(node.type(), node.config(), input);
                checkDeadline(deadline);
                outputs.put(node.id(), output.data());
                results.add(new NodeResult(node.id(), NodeStatus.COMPLETED, start, clock.instant(), input,
                        output.data(), output.message(), null));
            } catch (DeadlineExceeded ex) {
                error = "Execution timed out.";
                results.add(new NodeResult(node.id(), NodeStatus.FAILED, start, clock.instant(), input, null, null, error));
            } catch (RuntimeException ex) {
                error = "Node execution failed.";
                results.add(new NodeResult(node.id(), NodeStatus.FAILED, start, clock.instant(), input, null, null, error));
            }
        }
        return new Outcome(List.copyOf(results), error);
    }

    private void checkDeadline(Instant deadline) {
        if (!clock.instant().isBefore(deadline)) throw new DeadlineExceeded();
    }

    private static final class DeadlineExceeded extends RuntimeException {}
}
