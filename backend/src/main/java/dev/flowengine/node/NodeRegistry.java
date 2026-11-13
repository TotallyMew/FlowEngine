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
                             List<String> outputPorts, List<Field> fields) {
        Definition(String type, String label, String description, boolean input, List<String> outputPorts,
                   List<Field> fields) {
            this(type, label, description, input, !outputPorts.isEmpty(), outputPorts, fields);
        }
    }

    public record Output(Map<String, Object> data, String message, String port) {
        Output(Map<String, Object> data, String message) {
            this(data, message, "out");
        }
    }

    private interface NodeType {
        Definition definition();
        void validate(Map<String, Object> config);
        Output execute(Map<String, Object> config, Map<String, Object> input);
    }

    private static final String MANUAL = "MANUAL_TRIGGER";
    private final List<NodeType> types = List.of(new ManualTrigger(), new Condition(), new SetData(), new Log());

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
            return new Definition(MANUAL, "Manual Trigger", "Start a workflow with optional JSON input.",
                    false, List.of("out"), List.of());
        }

        @Override public void validate(Map<String, Object> config) {
            if (!config.isEmpty()) throw new GraphException("Manual Trigger has no configuration fields.");
        }

        @Override public Output execute(Map<String, Object> config, Map<String, Object> input) {
            return new Output(input, null);
        }
    }

    private static final class Condition implements NodeType {
        private static final Set<String> EMPTY_FIELDS = Set.of("field", "operator");
        private static final Set<String> EQUALS_FIELDS = Set.of("field", "operator", "value");

        @Override public Definition definition() {
            return new Definition("CONDITION", "Condition", "Route data based on a JSON field.",
                    true, List.of("true", "false"), List.of(
                    new Field("field", "Field name", "text", "A top-level JSON key."),
                    new Field("operator", "Operator", "select", "The check to apply to the field."),
                    new Field("value", "Text to match", "text", "Exact match for a string field.")));
        }

        @Override public void validate(Map<String, Object> config) {
            if (!(config.get("field") instanceof String field) || field.isBlank() || field.length() > 100) {
                throw new GraphException("Enter a top-level field name of at most 100 characters.");
            }
            if ("IS_EMPTY".equals(config.get("operator")) && config.keySet().equals(EMPTY_FIELDS)) return;
            if ("EQUALS".equals(config.get("operator")) && config.keySet().equals(EQUALS_FIELDS)
                    && config.get("value") instanceof String value && value.length() <= 1000) return;
            throw new GraphException("Select Is empty or provide a text value for Equals.");
        }

        @Override public Output execute(Map<String, Object> config, Map<String, Object> input) {
            Object value = input.get(config.get("field"));
            boolean matched = switch ((String) config.get("operator")) {
                case "IS_EMPTY" -> value == null || (value instanceof String text && text.isBlank());
                case "EQUALS" -> config.get("value").equals(value);
                default -> throw new IllegalStateException("Unknown condition operator.");
            };
            return new Output(input, null, Boolean.toString(matched));
        }
    }

    private static final class SetData implements NodeType {
        @Override public Definition definition() {
            return new Definition("SET_DATA", "Set Data", "Add or replace JSON fields.", true, List.of("out"),
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
            return new Definition("LOG", "Log", "Record a message in the run inspector.", true, List.of("out"),
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
