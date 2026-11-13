package dev.flowengine.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowService {
    public interface Summary {
        UUID getId();
        String getName();
        long getRevision();
        Instant getUpdatedAt();
    }

    public record Draft(UUID id, String name, WorkflowGraph graph, long revision, Instant updatedAt) {}

    private final WorkflowRepository repository;
    private final GraphValidator validator;

    WorkflowService(WorkflowRepository repository, GraphValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<Summary> list(String owner) {
        return repository.list(owner);
    }

    @Transactional
    public Draft create(String owner, String name) {
        return repository.saveAndFlush(new Workflow(owner, name)).draft();
    }

    @Transactional(readOnly = true)
    public Draft get(UUID id, String owner) {
        return owned(id, owner).draft();
    }

    @Transactional
    public Draft update(UUID id, String owner, String name, WorkflowGraph graph, long revision) {
        var workflow = owned(id, owner);
        if (workflow.draft().revision() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This workflow changed. Reload it and try again.");
        }
        validator.validate(graph, false);
        workflow.update(name, graph);
        repository.flush();
        return workflow.draft();
    }

    @Transactional
    public Draft duplicate(UUID id, String owner) {
        var original = owned(id, owner).draft();
        var workflow = new Workflow(owner, original.name());
        String name = original.name();
        int end = name.offsetByCodePoints(0, Math.min(93, name.codePointCount(0, name.length())));
        workflow.update(name.substring(0, end).stripTrailing() + " (copy)", original.graph());
        return repository.saveAndFlush(workflow).draft();
    }

    @Transactional
    public void delete(UUID id, String owner) {
        repository.delete(owned(id, owner));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Draft snapshot(UUID id, String owner, long revision) {
        var draft = repository.findForRun(id, owner).orElseThrow(WorkflowService::missing).draft();
        if (draft.revision() != revision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This workflow changed. Reload it before running.");
        }
        validator.validate(draft.graph(), true);
        return draft;
    }

    private Workflow owned(UUID id, String owner) {
        return repository.findByIdAndOwner(id, owner).orElseThrow(WorkflowService::missing);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found.");
    }
}
