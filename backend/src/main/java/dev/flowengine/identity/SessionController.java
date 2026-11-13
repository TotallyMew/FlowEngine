package dev.flowengine.identity;

import java.security.Principal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
class SessionController {
    record Session(String username) {}
    record Token(String headerName, String token) {}

    @GetMapping
    Session session(Principal principal) {
        return new Session(principal.getName());
    }

    @GetMapping("/csrf")
    Token csrf(CsrfToken token) {
        return new Token(token.getHeaderName(), token.getToken());
    }
}
