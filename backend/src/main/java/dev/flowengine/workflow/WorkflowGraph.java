package dev.flowengine.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record WorkflowGraph(
        @NotNull @Size(max = 50) List<@NotNull @Valid Node> nodes,
        @NotNull @Size(max = 49) List<@NotNull @Valid Edge> edges
) {
    public static WorkflowGraph empty() {
        return new WorkflowGraph(List.of(), List.of());
    }

    public record Node(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank String type,
            @NotBlank @Size(max = 80) String label,
            @NotNull @Valid Position position,
            @NotNull Map<String, Object> config
    ) {}

    public record Position(double x, double y) {}

    public record Edge(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank String source,
            @NotBlank String target,
            String sourcePort
    ) {
        public Edge {
            if (sourcePort == null) sourcePort = "out";
        }

        public Edge(String id, String source, String target) {
            this(id, source, target, "out");
        }
    }
}
