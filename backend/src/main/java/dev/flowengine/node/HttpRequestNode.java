package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HttpRequestNode implements NodeRegistry.NodeType {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final WorkflowHttpClient http;
    private final HttpDestination destination;

    HttpRequestNode(WorkflowHttpClient http, @Value("${flow.http.allowed-hosts}") String allowedHosts) {
        this.http = http;
        destination = new HttpDestination(allowedHosts, InetAddress::getAllByName);
    }

    @Override public NodeRegistry.Definition definition() {
        return new NodeRegistry.Definition("HTTP_REQUEST", "HTTP Request", "Call an allowed HTTPS endpoint.",
                true, List.of("out"), List.of(
                new NodeRegistry.Field("method", "Method", "select", "Choose an HTTP method."),
                new NodeRegistry.Field("url", "URL", "text", "Enter a URL on an allowed HTTPS host.")));
    }

    @Override public void validate(Map<String, Object> config) {
        if (!config.keySet().equals(Set.of("url", "method"))
                || !(config.get("method") instanceof String method) || !METHODS.contains(method)) {
            throw new GraphException("Select GET, POST, PUT, PATCH, or DELETE.");
        }
        HttpDestination.parse(config.get("url"));
    }

    @Override public NodeRegistry.Output execute(Map<String, Object> config, Map<String, Object> input, Instant deadline) {
        var uri = HttpDestination.parse(config.get("url"));
        var method = (String) config.get("method");
        var response = http.send(destination, uri, method, BODY_METHODS.contains(method) ? input : null,
                Map.of(), deadline);
        var output = new LinkedHashMap<String, Object>();
        output.put("status", response.status());
        output.put("body", response.body());
        return new NodeRegistry.Output(output, null);
    }
}
