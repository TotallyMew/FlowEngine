package dev.flowengine.node;

import dev.flowengine.workflow.GraphException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class HttpDestinationTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.com/", "https://localhost/", "https://127.0.0.1/",
            "https://[::1]/", "https://user@api.example.com/", "https://api.example.com:8443/",
            "https://api.example.com/#fragment", "https://api.example.com./", "https://api.example.com:/"})
    void rejectsUnsafeUrlForms(String value) {
        assertThatThrownBy(() -> HttpDestination.parse(value)).isInstanceOf(GraphException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.168.0.1", "192.0.0.192", "168.63.129.16", "198.18.0.1", "224.0.0.1",
            "::1", "::ffff:127.0.0.1", "fd00:ec2::254", "fe80::1", "64:ff9b::7f00:1", "2002:7f00:1::1", "2001:db8::1"})
    void blocksPrivateAndSpecialPurposeAddresses(String address) throws Exception {
        assertThat(HttpDestination.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @Test
    void checksAllDnsAnswersAndPinsAnApprovedAddressToTheHostname() throws Exception {
        var publicAddress = InetAddress.getByName("93.184.215.14");
        var publicIpv6 = InetAddress.getByName("2606:4700::1111");
        var localAddress = InetAddress.getLoopbackAddress();
        var uri = HttpDestination.parse("https://api.example.com:443/path");
        var mixed = new HttpDestination("api.example.com", host -> new InetAddress[]{publicAddress, localAddress});
        assertThatThrownBy(() -> mixed.resolve(uri)).hasMessageContaining("restricted address");

        var destination = new HttpDestination(" API.example.com ,", host -> new InetAddress[]{publicAddress, publicIpv6});
        var target = destination.resolve(uri);
        assertThat(target.getAddress()).isEqualTo(publicAddress);
        assertThat(target.getHostName()).isEqualTo("api.example.com");
        assertThat(target.getPort()).isEqualTo(443);

        var other = new HttpDestination("example.com", host -> {
            throw new AssertionError("Disallowed hostname must be rejected before DNS.");
        });
        assertThatThrownBy(() -> other.resolve(uri)).hasMessageContaining("allowlist");
    }
}
