package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NodeRegistry {
    public record Field(String key, String label, String kind, String description) {}
    public record Definition(String type, String label, String description, boolean input, boolean output,
                             List<Field> fields) {}
    public record Output(Map<String, Object> data, String message) {}

    private interface NodeType {
        Definition definition();
        void validate(Map<String, Object> config);
        Output execute(Map<String, Object> config, Map<String, Object> input);
    }

    private static final String MANUAL = "MANUAL_TRIGGER";
    private final List<NodeType> types = List.of(new ManualTrigger(), new SetData(), new Log());

    public List<Definition> definitions() {
        return types.stream().map(NodeType::definition).toList();
    }

    public Definition definition(String type) {
        return find(type).definition();
    }

    public void validate(String type, Map<String, Object> config) {
        find(type).validate(config);
    }

    public Output execute(String type, Map<String, Object> config, Map<String, Object> input) {
        return find(type).execute(config, input);
    }

    private NodeType find(String type) {
        return types.stream().filter(node -> node.definition().type().equals(type)).findFirst()
                .orElseThrow(() -> new GraphException("Unsupported node type: " + type));
    }

    private static final class ManualTrigger implements NodeType {
        @Override public Definition definition() {
            return new Definition(MANUAL, "Manual Trigger", "Start a workflow on demand.", false, true, List.of());
        }

        @Override public void validate(Map<String, Object> config) {
            if (!config.isEmpty()) throw new GraphException("Manual Trigger has no configuration fields.");
        }

        @Override public Output execute(Map<String, Object> config, Map<String, Object> input) {
            return new Output(Map.of(), null);
        }
    }

    private static final class SetData implements NodeType {
        @Override public Definition definition() {
            return new Definition("SET_DATA", "Set Data", "Add or replace JSON fields.", true, true,
                    List.of(new Field("values", "Values", "json", "Top-level fields replace matching input fields.")));
        }

        @Override public void validate(Map<String, Object> config) {
            if (!config.keySet().equals(Set.of("values")) || !(config.get("values") instanceof Map<?, ?> values)
                    || values.size() > 100) {
                throw new GraphException("Set Data requires a JSON object with at most 100 top-level fields.");
            }
        }

        @Override public Output execute(Map<String, Object> config, Map<String, Object> input) {
            var result = new LinkedHashMap<>(input);
            var values = (Map<?, ?>) config.get("values");
            values.forEach((key, value) -> result.put((String) key, value));
            return new Output(Collections.unmodifiableMap(result), null);
        }
    }

    private static final class Log implements NodeType {
        @Override public Definition definition() {
            return new Definition("LOG", "Log", "Record a message in the run inspector.", true, true,
                    List.of(new Field("message", "Message", "text", "Recorded with this node’s execution.")));
        }

        @Override public void validate(Map<String, Object> config) {
            if (!config.keySet().equals(Set.of("message")) || !(config.get("message") instanceof String message)
                    || message.isBlank() || message.length() > 1000) {
                throw new GraphException("Log requires a nonblank message of at most 1000 characters.");
            }
        }

        @Override public Output execute(Map<String, Object> config, Map<String, Object> input) {
            return new Output(input, (String) config.get("message"));
        }
    }
}
