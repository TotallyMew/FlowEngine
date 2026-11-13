package dev.flowengine.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class WebhookController {
    record Register(@NotNull @PositiveOrZero Long revision) {}

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping("/api/workflows/{id}/webhook")
    WebhookService.Status status(@PathVariable UUID id, Principal user) {
        return webhooks.status(id, user.getName());
    }

    @PutMapping("/api/workflows/{id}/webhook")
    ResponseEntity<WebhookService.Registration> register(@PathVariable UUID id, Principal user,
                                                          @Valid @RequestBody Register request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(webhooks.register(id, user.getName(), request.revision()));
    }

    @DeleteMapping("/api/workflows/{id}/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id, Principal user) {
        webhooks.revoke(id, user.getName());
    }

    @PostMapping(path = "/api/webhooks/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    WebhookService.Accepted receive(@PathVariable UUID id,
                                    @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @RequestBody Map<String, Object> input) {
        return webhooks.receive(id, authorization, input);
    }
}
