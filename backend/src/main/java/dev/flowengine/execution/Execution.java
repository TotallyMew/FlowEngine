package dev.flowengine.execution;

import dev.flowengine.workflow.WorkflowGraph;
import dev.flowengine.workflow.WorkflowService.Draft;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "executions")
class Execution {
    enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    @Id private UUID id;
    private UUID workflowId;
    private long workflowRevision;
    @JdbcTypeCode(SqlTypes.JSON) private WorkflowGraph graph;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> input;
    @Enumerated(EnumType.STRING) private Status status;
    private Instant requestedAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant nextDispatchAt;
    private Instant deadlineAt;
    @JdbcTypeCode(SqlTypes.JSON) private List<ExecutionEngine.NodeResult> results;
    private String error;

    protected Execution() {}

    Execution(Draft draft, Map<String, Object> input) {
        id = UUID.randomUUID();
        workflowId = draft.id();
        workflowRevision = draft.revision();
        graph = draft.graph();
        this.input = input;
        status = Status.PENDING;
        requestedAt = Instant.now();
        nextDispatchAt = requestedAt;
        results = List.of();
    }

    void finish(ExecutionEngine.Outcome outcome, Instant now) {
        results = outcome.results();
        error = outcome.error();
        if (error == null && !now.isBefore(deadlineAt)) error = "Execution timed out.";
        status = error == null ? Status.COMPLETED : Status.FAILED;
        finishedAt = now;
    }

    ExecutionService.Detail detail() {
        return new ExecutionService.Detail(id, workflowId, workflowRevision, graph, status.name(), requestedAt,
                startedAt, finishedAt, input, results, error);
    }

    ExecutionService.Claim claim() {
        return new ExecutionService.Claim(graph, input, deadlineAt);
    }
}
