package dev.flowengine.node;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class HttpRequestNodeTest {
    private final WorkflowHttpClient http = new WorkflowHttpClient(new ObjectMapper());
    private final HttpRequestNode node = new HttpRequestNode(http, "httpbingo.org");

    @AfterEach
    void close() throws Exception {
        http.close();
    }

    @Test
    void readsJsonTextAndEmptyResponses() throws Exception {
        var response = new BasicClassicHttpResponse(201);
        response.setEntity(new StringEntity("{\"sku\":\"BIKE-123\"}", ContentType.APPLICATION_JSON));
        assertThat(http.readResponse(response)).isEqualTo(new WorkflowHttpClient.Response(201, Map.of("sku", "BIKE-123")));

        response.setEntity(new StringEntity("Sveiki", ContentType.TEXT_PLAIN));
        assertThat(http.readResponse(response)).isEqualTo(new WorkflowHttpClient.Response(201, "Sveiki"));

        var empty = new BasicClassicHttpResponse(204);
        assertThat(http.readResponse(empty)).isEqualTo(new WorkflowHttpClient.Response(204, null));
    }

    @Test
    void limitsUnknownLengthBodiesAndRejectsUnsafeJsonAndRedirects() {
        var response = new BasicClassicHttpResponse(200);
        var bytes = new ByteArrayInputStream(new byte[64 * 1024 + 2]);
        response.setEntity(new InputStreamEntity(bytes, -1, ContentType.TEXT_PLAIN));
        assertThatThrownBy(() -> http.readResponse(response)).hasMessageContaining("64 KiB");
        assertThat(bytes.available()).isEqualTo(1);

        response.setEntity(new StringEntity("{\"key\":1} {\"extra\":2}", ContentType.APPLICATION_JSON));
        assertThatThrownBy(() -> http.readResponse(response)).hasMessageContaining("invalid JSON");
        response.setEntity(new StringEntity("{\"key\":\"\\u0000\"}", ContentType.APPLICATION_JSON));
        assertThatThrownBy(() -> http.readResponse(response)).hasMessageContaining("unsupported text");

        var redirect = new BasicClassicHttpResponse(302);
        redirect.setHeader("Location", "http://169.254.169.254/");
        assertThatThrownBy(() -> http.readResponse(redirect)).hasMessage("HTTP endpoint returned status 302.");
    }

    @Test
    void rejectsUnlistedHostsAndExpiredRuns() {
        var input = Map.<String, Object>of("sku", "BIKE-123");
        assertThatThrownBy(() -> node.execute(Map.of("method", "POST", "url", "https://api.example.com/"), input,
                Instant.now().plusSeconds(5))).hasMessageContaining("allowlist");
        assertThatThrownBy(() -> node.execute(Map.of("method", "POST", "url", "https://httpbingo.org/post"), input,
                Instant.now().minusMillis(1))).hasMessageContaining("timed out");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FLOW_HTTP_LIVE_TEST", matches = "true")
    void postsJsonToAnApprovedHttpsEndpointAndDoesNotFollowRedirects() {
        var input = Map.<String, Object>of("productCode", "FLOWENGINE-TEST", "priceEur", 1299);
        var output = node.execute(Map.of("method", "POST", "url", "https://httpbingo.org/post"), input,
                Instant.now().plusSeconds(12));
        assertThat(output.data()).containsEntry("status", 200);
        assertThat(((Map<?, ?>) output.data().get("body")).get("json")).isEqualTo(input);

        var redirect = Map.<String, Object>of("method", "GET", "url", "https://httpbingo.org/relative-redirect/1");
        assertThatThrownBy(() -> node.execute(redirect, Map.of(), Instant.now().plusSeconds(12)))
                .hasMessage("HTTP endpoint returned status 302.");
    }
}
