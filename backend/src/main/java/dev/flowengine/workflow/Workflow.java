package dev.flowengine.workflow;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflows")
class Workflow {
    @Id private UUID id;
    private String owner;
    private String name;
    @JdbcTypeCode(SqlTypes.JSON) private WorkflowGraph graph;
    @Version private long revision;
    private Instant updatedAt;

    protected Workflow() {}

    Workflow(String owner, String name) {
        id = UUID.randomUUID();
        this.owner = owner;
        update(name, WorkflowGraph.empty());
    }

    void update(String name, WorkflowGraph graph) {
        this.name = name.strip();
        this.graph = graph;
        updatedAt = Instant.now();
    }

    WorkflowService.Draft draft() {
        return new WorkflowService.Draft(id, name, graph, revision, updatedAt);
    }
}
