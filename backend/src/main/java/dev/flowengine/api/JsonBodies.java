package dev.flowengine.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice
class JsonBodies extends RequestBodyAdviceAdapter {
    private static final int LIMIT = 128 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        if (input.getHeaders().getContentLength() > LIMIT) throw tooLarge();
        byte[] bytes = input.getBody().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) throw tooLarge();
        return new HttpInputMessage() {
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        };
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Request body exceeds 128 KiB.");
    }
}
