package dev.flowengine.execution;

import dev.flowengine.workflow.WorkflowGraph;
import dev.flowengine.workflow.WorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExecutionService {
    public interface Summary {
        UUID getId();
        String getStatus();
        long getWorkflowRevision();
        Instant getRequestedAt();
        Instant getStartedAt();
        Instant getFinishedAt();
    }

    public record Detail(UUID id, UUID workflowId, long workflowRevision, WorkflowGraph graph, String status,
                         Instant requestedAt, Instant startedAt, Instant finishedAt,
                         Map<String, Object> input,
                         List<ExecutionEngine.NodeResult> results, String error) {}

    public record Claim(WorkflowGraph graph, Map<String, Object> input, Instant deadline) {}

    private final ExecutionRepository executions;
    private final WorkflowService workflows;

    ExecutionService(ExecutionRepository executions, WorkflowService workflows) {
        this.executions = executions;
        this.workflows = workflows;
    }

    @Transactional
    public Detail request(UUID workflowId, String owner, long revision, Map<String, Object> input) {
        var snapshot = workflows.snapshot(workflowId, owner, revision);
        return executions.save(new Execution(snapshot, input)).detail();
    }

    @Transactional(readOnly = true)
    public List<Summary> list(UUID workflowId, String owner, int page) {
        workflows.get(workflowId, owner);
        return executions.list(workflowId, PageRequest.of(page, 20));
    }

    @Transactional(readOnly = true)
    public Detail get(UUID workflowId, UUID id, String owner) {
        workflows.get(workflowId, owner);
        return executions.findByIdAndWorkflowId(id, workflowId).map(Execution::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found."));
    }

    @Transactional
    public Optional<Claim> claim(UUID id) {
        var now = Instant.now();
        if (executions.claim(id, now, now.plusSeconds(30)) == 0) return Optional.empty();
        return executions.findById(id).map(Execution::claim);
    }

    @Transactional
    public void finish(UUID id, ExecutionEngine.Outcome outcome) {
        executions.findRunningForUpdate(id).ifPresent(run -> run.finish(outcome, Instant.now()));
    }

    @Transactional
    public int expire() {
        return executions.expire(Instant.now());
    }
}
