package dev.flowengine.execution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows/{workflowId}/executions")
class ExecutionController {
    record RunRequest(@NotNull @PositiveOrZero Long revision, Map<String, Object> input) {
        RunRequest {
            if (input == null) input = Map.of();
        }
    }

    private final ExecutionService runs;

    ExecutionController(ExecutionService runs) {
        this.runs = runs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ExecutionService.Detail request(Principal user, @PathVariable UUID workflowId, @Valid @RequestBody RunRequest body) {
        return runs.request(workflowId, user.getName(), body.revision(), body.input());
    }

    @GetMapping
    List<ExecutionService.Summary> list(Principal user, @PathVariable UUID workflowId,
                                        @RequestParam(defaultValue = "0") @PositiveOrZero int page) {
        return runs.list(workflowId, user.getName(), page);
    }

    @GetMapping("/{id}")
    ExecutionService.Detail get(Principal user, @PathVariable UUID workflowId, @PathVariable UUID id) {
        return runs.get(workflowId, id, user.getName());
    }
}
