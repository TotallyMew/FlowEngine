package dev.flowengine.webhook;

import dev.flowengine.execution.ExecutionService;
import dev.flowengine.node.NodeRegistry;
import dev.flowengine.workflow.WorkflowService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class WebhookService {
    record Status(String path, Long revision) {}
    record Registration(String path, long revision, String token) {}
    record Accepted(UUID executionId) {}

    private static final int REQUESTS_PER_MINUTE = 30;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final WorkflowService workflows;
    private final ExecutionService executions;

    WebhookService(JdbcClient jdbc, WorkflowService workflows, ExecutionService executions) {
        this.jdbc = jdbc;
        this.workflows = workflows;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public Status status(UUID id, String owner) {
        workflows.get(id, owner);
        Long revision = jdbc.sql("SELECT revision FROM webhook_endpoints WHERE workflow_id = :id")
                .param("id", id).query(Long.class).optional().orElse(null);
        return new Status(path(id), revision);
    }

    @Transactional
    public Registration register(UUID id, String owner, long revision) {
        workflows.snapshot(id, owner, revision, NodeRegistry.WEBHOOK_TRIGGER);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.sql("""
                INSERT INTO webhook_endpoints (workflow_id, revision, token_hash)
                VALUES (:id, :revision, :hash)
                ON CONFLICT (workflow_id) DO UPDATE
                SET revision = excluded.revision, token_hash = excluded.token_hash
                """)
                .param("id", id).param("revision", revision).param("hash", hash(token)).update();
        return new Registration(path(id), revision, token);
    }

    @Transactional
    public void revoke(UUID id, String owner) {
        workflows.get(id, owner);
        jdbc.sql("DELETE FROM webhook_endpoints WHERE workflow_id = :id").param("id", id).update();
    }

    @Transactional
    public Accepted receive(UUID id, String authorization, Map<String, Object> input) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                || !TOKEN.matcher(authorization.substring(7)).matches()) throw unavailable();
        String digest = hash(authorization.substring(7));
        long revision = jdbc.sql("""
                SELECT revision FROM webhook_endpoints WHERE workflow_id = :id AND token_hash = :hash
                """).param("id", id).param("hash", digest).query(Long.class).optional().orElseThrow(WebhookService::unavailable);
        var snapshot = workflows.webhookSnapshot(id, revision);
        int updated = jdbc.sql("""
                UPDATE webhook_endpoints
                SET request_count = CASE WHEN window_started_at <= now() - interval '1 minute'
                    THEN 1 ELSE request_count + 1 END,
                    window_started_at = CASE WHEN window_started_at <= now() - interval '1 minute'
                    THEN now() ELSE window_started_at END
                WHERE workflow_id = :id AND token_hash = :hash AND revision = :revision
                  AND (request_count < :limit OR window_started_at <= now() - interval '1 minute')
                """).param("id", id).param("hash", digest).param("revision", revision)
                .param("limit", REQUESTS_PER_MINUTE).update();
        if (updated == 0) {
            boolean active = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM webhook_endpoints
                        WHERE workflow_id = :id AND token_hash = :hash AND revision = :revision)
                    """).param("id", id).param("hash", digest).param("revision", revision).query(Boolean.class).single();
            if (!active) throw unavailable();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Webhook limit is 30 requests per minute.");
        }
        return new Accepted(executions.enqueue(snapshot, input).id());
    }

    private static String path(UUID id) {
        return "/api/webhooks/" + id;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook unavailable.");
    }

    private static String hash(String token) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
