package dev.flowengine.workflow;

import dev.flowengine.node.NodeRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class WorkflowController {
    record Create(@NotBlank @Size(max = 100) String name) {}
    record Update(@NotBlank @Size(max = 100) String name, @NotNull @Valid WorkflowGraph graph,
                  @NotNull @PositiveOrZero Long revision) {}

    private final WorkflowService workflows;
    private final NodeRegistry nodes;

    WorkflowController(WorkflowService workflows, NodeRegistry nodes) {
        this.workflows = workflows;
        this.nodes = nodes;
    }

    @GetMapping("/node-types")
    List<NodeRegistry.Definition> nodeTypes() {
        return nodes.definitions();
    }

    @GetMapping("/workflows")
    List<WorkflowService.Summary> list(Principal user) {
        return workflows.list(user.getName());
    }

    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.CREATED)
    WorkflowService.Draft create(Principal user, @Valid @RequestBody Create request) {
        return workflows.create(user.getName(), request.name());
    }

    @GetMapping("/workflows/{id}")
    WorkflowService.Draft get(Principal user, @PathVariable UUID id) {
        return workflows.get(id, user.getName());
    }

    @PutMapping("/workflows/{id}")
    WorkflowService.Draft update(Principal user, @PathVariable UUID id, @Valid @RequestBody Update request) {
        return workflows.update(id, user.getName(), request.name(), request.graph(), request.revision());
    }

    @PostMapping("/workflows/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    WorkflowService.Draft duplicate(Principal user, @PathVariable UUID id) {
        return workflows.duplicate(id, user.getName());
    }

    @DeleteMapping("/workflows/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal user, @PathVariable UUID id) {
        workflows.delete(id, user.getName());
    }
}
