package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubCreateIssueNodeTest {
    private static final Map<String, Object> CONFIG = Map.of(
            "repository", "Example/Items", "titleField", "issueTitle", "bodyField", "description");
    private static final Map<String, Object> INPUT = Map.of(
            "issueTitle", "Missing SKU", "description", "**Check** the item.", "internal", "exclude this");
    private static final URI ISSUES = URI.create("https://api.github.com/repos/example/items/issues");
    private static final Map<String, String> HEADERS = Map.of(
            "Accept", "application/vnd.github+json", "Authorization", "Bearer test_token",
            "X-GitHub-Api-Version", "2026-03-10");
    private final WorkflowHttpClient http = mock(WorkflowHttpClient.class);
    private final GitHubCreateIssueNode node = new GitHubCreateIssueNode(http, "example/ITEMS", "test_token");
    private final Instant deadline = Instant.now().plusSeconds(20);

    @Test
    void sendsOnlyMappedFieldsAndReturnsAnIssueLink() {
        var payload = Map.<String, Object>of("title", "Missing SKU", "body", "**Check** the item.");
        when(http.send(any(), eq(ISSUES), eq("POST"), eq(payload), eq(HEADERS), eq(deadline)))
                .thenReturn(new WorkflowHttpClient.Response(201, Map.of("number", 42, "extra", "discard")));
        node.validate(CONFIG);

        assertThat(node.execute(CONFIG, INPUT, deadline).data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "repository", "example/items", "number", 42, "url", "https://github.com/example/items/issues/42"));
        verify(http).send(any(), eq(ISSUES), eq("POST"), eq(payload), eq(HEADERS), eq(deadline));
    }

    @Test
    void supportsAnOptionalDescriptionAndRejectsAnUnexpectedResponse() {
        var config = Map.<String, Object>of("repository", "Example/Items", "titleField", "issueTitle", "bodyField", "");
        var payload = Map.<String, Object>of("title", "Missing SKU");
        when(http.send(any(), eq(ISSUES), eq("POST"), eq(payload), eq(HEADERS), eq(deadline)))
                .thenReturn(new WorkflowHttpClient.Response(201, Map.of("number", "42")));
        node.validate(config);
        assertThatThrownBy(() -> node.execute(config, INPUT, deadline)).hasMessageContaining("unexpected issue response");
        verify(http).send(any(), eq(ISSUES), eq("POST"), eq(payload), eq(HEADERS), eq(deadline));
    }

    @Test
    void rejectsUnsafeConfigurationAndMissingInputBeforeSending() {
        var wrongRepo = Map.<String, Object>of("repository", "Example/Other", "titleField", "issueTitle", "bodyField", "");
        var badRepo = Map.<String, Object>of("repository", "example/..", "titleField", "issueTitle", "bodyField", "");
        assertThatThrownBy(() -> node.validate(badRepo)).isInstanceOf(GraphException.class);
        assertThatThrownBy(() -> node.execute(wrongRepo, INPUT, deadline)).hasMessageContaining("outside the server");
        assertThatThrownBy(() -> node.execute(CONFIG, Map.of(), deadline)).hasMessageContaining("title");
        assertThatThrownBy(() -> node.execute(CONFIG, Map.of("issueTitle", "Valid", "description", 5), deadline))
                .hasMessageContaining("description");
        var disabled = new GitHubCreateIssueNode(http, "example/items", "");
        assertThatThrownBy(() -> disabled.execute(CONFIG, INPUT, deadline)).hasMessageContaining("not configured");
        verifyNoInteractions(http);
    }
}
