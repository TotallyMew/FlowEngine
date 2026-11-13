package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.hc.core5.http.HttpHost;

final class HttpDestination {
    private static final Pattern DNS_NAME = Pattern.compile(
            "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}");

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Set<String> allowed;
    private final Resolver resolver;

    HttpDestination(String allowedHosts, Resolver resolver) {
        this.resolver = resolver;
        allowed = Arrays.stream(allowedHosts.split(",", -1)).map(String::strip)
                .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.stream().anyMatch(host -> !DNS_NAME.matcher(host).matches())) {
            throw new IllegalArgumentException("FLOW_HTTP_ALLOWED_HOSTS must contain exact DNS hostnames.");
        }
    }

    static URI parse(Object value) {
        if (value instanceof String url && url.length() <= 2048) {
            try {
                var uri = URI.create(url);
                if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && DNS_NAME.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()
                        && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                        && (uri.getRawAuthority().equalsIgnoreCase(uri.getHost())
                        || uri.getRawAuthority().equalsIgnoreCase(uri.getHost() + ":443"))) return uri;
            } catch (IllegalArgumentException ignored) {
                // Use the same validation message for malformed and unsupported URLs.
            }
        }
        throw new GraphException("Enter an HTTPS URL with a DNS hostname and the default HTTPS port.");
    }

    HttpHost resolve(URI uri) {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowed.contains(host)) throw new NodeExecutionException("HTTP destination is outside the server allowlist.");
        try {
            var addresses = resolver.resolve(host);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
                throw new NodeExecutionException("HTTP destination resolved to a restricted address.");
            }
            // Pass the vetted IP to the transport and retain the hostname for TLS and Host.
            return new HttpHost("https", addresses[0], host, 443);
        } catch (UnknownHostException ex) {
            throw new NodeExecutionException("HTTP destination could not be resolved.");
        }
    }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 16) {
            // Permit global unicast and exclude special-purpose, transition and documentation ranges.
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && second == 1 && (third < 2 || (third == 0x0d && bytes[3] == (byte) 0xb8)))
                    && !(first == 0x20 && second == 2)
                    && !(first == 0x3f && second == 0xff && third < 0x10);
        }
        return first > 0 && first < 224 && first != 10 && first != 127
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && (second == 168 || (second == 0 && (third == 0 || third == 2))
                    || (second == 88 && third == 99)))
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                && !(first == 203 && second == 0 && third == 113)
                && !(first == 168 && second == 63 && third == 129 && bytes[3] == 16);
    }
}
