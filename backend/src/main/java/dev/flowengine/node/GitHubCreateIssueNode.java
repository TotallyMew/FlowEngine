package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class GitHubCreateIssueNode implements NodeRegistry.NodeType {
    private static final Pattern REPOSITORY = Pattern.compile(
            "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,37}[a-zA-Z0-9])?/[a-zA-Z0-9_.-]{1,100}");
    private static final Set<String> CONFIG = Set.of("repository", "titleField", "bodyField");
    private static final int MAX_TITLE = 256;
    private static final int MAX_BODY = 8000;

    private final WorkflowHttpClient http;
    private final HttpDestination destination = new HttpDestination("api.github.com", InetAddress::getAllByName);
    private final String allowedRepository;
    private final String token;

    GitHubCreateIssueNode(WorkflowHttpClient http,
                          @Value("${flow.github.repository}") String repository,
                          @Value("${flow.github.token}") String token) {
        this.http = http;
        allowedRepository = repository.strip().toLowerCase(Locale.ROOT);
        this.token = token.strip();
        if (!allowedRepository.isEmpty() && !validRepository(allowedRepository)) {
            throw new IllegalArgumentException("FLOW_GITHUB_REPOSITORY must use owner/repository format.");
        }
        if (!this.token.isEmpty() && !this.token.matches("[A-Za-z0-9_]{1,512}")) {
            throw new IllegalArgumentException("FLOW_GITHUB_TOKEN must contain a GitHub access token.");
        }
    }

    @Override public NodeRegistry.Definition definition() {
        return new NodeRegistry.Definition("GITHUB_CREATE_ISSUE", "GitHub · Create issue", "Open an issue from JSON fields.",
                true, List.of("out"), List.of(
                new NodeRegistry.Field("repository", "Repository", "text", "Use owner/repository."),
                new NodeRegistry.Field("titleField", "Title field", "text", "Top-level JSON key containing the issue title."),
                new NodeRegistry.Field("bodyField", "Description field", "text", "Optional top-level JSON key containing Markdown.")));
    }

    @Override public void validate(Map<String, Object> config) {
        if (!CONFIG.equals(config.keySet()) || !(config.get("repository") instanceof String repository)
                || !validRepository(repository)) {
            throw new GraphException("Enter a GitHub repository in owner/repository format.");
        }
        if (!(config.get("titleField") instanceof String title) || title.isBlank() || title.length() > 100
                || !(config.get("bodyField") instanceof String body) || body.length() > 100
                || (!body.isEmpty() && body.isBlank())) {
            throw new GraphException("Use a title field and an optional description field of at most 100 characters each.");
        }
    }

    @Override public NodeRegistry.Output execute(Map<String, Object> config, Map<String, Object> input, Instant deadline) {
        if (allowedRepository.isEmpty() || token.isEmpty()) {
            throw new NodeExecutionException("GitHub connector is not configured on the server.");
        }
        String repository = ((String) config.get("repository")).toLowerCase(Locale.ROOT);
        if (!repository.equals(allowedRepository)) {
            throw new NodeExecutionException("GitHub repository is outside the server configuration.");
        }
        if (!(input.get(config.get("titleField")) instanceof String title) || title.isBlank() || title.length() > MAX_TITLE) {
            throw new NodeExecutionException("GitHub issue title must be nonblank text of at most 256 characters.");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        var bodyField = (String) config.get("bodyField");
        if (!bodyField.isEmpty()) {
            if (!(input.get(bodyField) instanceof String body) || body.length() > MAX_BODY) {
                throw new NodeExecutionException("GitHub issue description must be text of at most 8000 characters.");
            }
            payload.put("body", body);
        }
        var uri = URI.create("https://api.github.com/repos/" + repository + "/issues");
        var response = http.send(destination, uri, "POST", payload, Map.of(
                "Accept", "application/vnd.github+json",
                "Authorization", "Bearer " + token,
                "X-GitHub-Api-Version", "2026-03-10"), deadline);
        if (response.status() != 201 || !(response.body() instanceof Map<?, ?> issue)
                || !(issue.get("number") instanceof Number number)
                || !(number instanceof Integer || number instanceof Long) || number.longValue() <= 0) {
            throw new NodeExecutionException("GitHub returned an unexpected issue response.");
        }
        return new NodeRegistry.Output(Map.of(
                "repository", repository,
                "number", number,
                "url", "https://github.com/" + repository + "/issues/" + number), null);
    }

    private static boolean validRepository(String value) {
        return REPOSITORY.matcher(value).matches() && !value.endsWith("/.") && !value.endsWith("/..");
    }
}
