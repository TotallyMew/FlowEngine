package dev.flowengine.node;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.NoConnectionReuseStrategy;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
class WorkflowHttpClient {
    record Response(int status, Object body) {}

    private static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration MAX_DURATION = Duration.ofSeconds(10);
    private static final Timeout IO_TIMEOUT = Timeout.ofSeconds(5);

    private final ObjectMapper json;
    private final CloseableHttpClient client;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("flowengine-http-", 0).factory());

    WorkflowHttpClient(ObjectMapper json) {
        this.json = json;
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new DnsResolver() {
                    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                        throw new UnknownHostException("HTTP transport requires an approved address.");
                    }

                    @Override public String resolveCanonicalHostname(String host) {
                        return host;
                    }
                })
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
                        .setMaxHeaderCount(64).setMaxLineLength(8192).build()).build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(IO_TIMEOUT).setSocketTimeout(IO_TIMEOUT).build()).build();
        client = HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(IO_TIMEOUT)
                        .setResponseTimeout(IO_TIMEOUT).build())
                .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().build();
    }

    Response send(HttpDestination destination, URI uri, String method, Map<String, Object> body,
                  Map<String, String> headers, Instant deadline) {
        long budget = Math.min(MAX_DURATION.toNanos(), Duration.between(Instant.now(), deadline).toNanos());
        if (budget <= 0) throw new NodeExecutionException("Execution timed out.");
        long end = System.nanoTime() + budget;
        var request = new HttpUriRequestBase(method, uri);
        request.setHeader("Accept", "application/json, text/plain");
        request.setHeader("Accept-Encoding", "identity");
        headers.forEach(request::setHeader);
        if (body != null) {
            byte[] bytes = json.writeValueAsBytes(body);
            if (bytes.length > MAX_REQUEST_BYTES) throw new NodeExecutionException("HTTP request body exceeds 128 KiB.");
            request.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_JSON));
        }

        try {
            var task = requests.submit(() -> exchange(request, destination, uri, end));
            try {
                return task.get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                request.cancel();
                task.cancel(true);
                throw new NodeExecutionException("HTTP request timed out.");
            } catch (InterruptedException ex) {
                request.cancel();
                task.cancel(true);
                Thread.currentThread().interrupt();
                throw new NodeExecutionException("HTTP request interrupted.");
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof NodeExecutionException safe) throw safe;
                if (ex.getCause() instanceof java.io.InterruptedIOException) {
                    throw new NodeExecutionException("HTTP request timed out.");
                }
                throw new NodeExecutionException("HTTP connection or response failed.");
            }
        } catch (RejectedExecutionException ex) {
            throw new NodeExecutionException("HTTP request capacity is busy.");
        }
    }

    private Response exchange(HttpUriRequestBase request, HttpDestination destination, URI uri, long end) throws IOException {
        var target = destination.resolve(uri);
        if (end - System.nanoTime() <= 0 || request.isCancelled()) throw new NodeExecutionException("HTTP request timed out.");
        return client.execute(target, request, response -> {
            try {
                return readResponse(response);
            } catch (IOException | RuntimeException ex) {
                // Discard the connection immediately, including unread oversized or error bodies.
                request.cancel();
                throw ex;
            }
        });
    }

    Response readResponse(ClassicHttpResponse response) throws IOException {
        int status = response.getCode();
        if (status < 200 || status >= 300) throw new NodeExecutionException("HTTP endpoint returned status " + status + ".");
        var entity = response.getEntity();
        Object body = null;
        if (entity != null) {
            if (entity.getContentEncoding() != null && !"identity".equalsIgnoreCase(entity.getContentEncoding())) {
                throw new NodeExecutionException("HTTP endpoint must return an uncompressed response.");
            }
            if (entity.getContentLength() > MAX_RESPONSE_BYTES) throw oversizedResponse();
            byte[] bytes = entity.getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw oversizedResponse();
            if (bytes.length > 0) {
                var type = ContentType.parse(entity.getContentType() == null ? "text/plain" : entity.getContentType());
                var mime = type.getMimeType();
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (mime.equals("application/json") || mime.endsWith("+json")) {
                    try {
                        body = json.readerFor(Object.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
                    } catch (tools.jackson.core.JacksonException ex) {
                        throw new NodeExecutionException("HTTP endpoint returned invalid JSON.");
                    }
                } else if (mime.startsWith("text/")) {
                    body = text;
                } else {
                    throw new NodeExecutionException("HTTP endpoint must return JSON or UTF-8 text.");
                }
                if (!canStore(body)) throw new NodeExecutionException("HTTP response contains unsupported text characters.");
            }
        }
        return new Response(status, body);
    }

    private static boolean canStore(Object value) {
        return switch (value) {
            case String text -> text.codePoints().noneMatch(cp -> cp == 0 || (cp >= 0xd800 && cp <= 0xdfff));
            case Map<?, ?> map -> map.entrySet().stream().allMatch(entry -> canStore(entry.getKey()) && canStore(entry.getValue()));
            case List<?> list -> list.stream().allMatch(WorkflowHttpClient::canStore);
            case Double number -> Double.isFinite(number);
            case java.math.BigDecimal number -> number.scale() <= 16383
                    && (long) number.precision() - number.scale() <= 131072;
            case null, default -> true;
        };
    }

    private static NodeExecutionException oversizedResponse() {
        return new NodeExecutionException("HTTP response exceeds 64 KiB.");
    }

    @PreDestroy
    void close() throws IOException {
        requests.shutdownNow();
        client.close();
    }
}
