package org.argos.argos_backend.external.http;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.DnsResolver;
import org.argos.argos_backend.global.exception.ApiException;
import org.springframework.stereotype.Component;

@Component
public class SafeUrlPolicy implements DnsResolver {
    @FunctionalInterface public interface Lookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Lookup lookup;
    public SafeUrlPolicy() { this(InetAddress::getAllByName); }
    SafeUrlPolicy(Lookup lookup) { this.lookup = lookup; }

    public URI validate(String value) {
        final URI uri;
        try { uri = URI.create(value).normalize(); }
        catch (IllegalArgumentException e) { throw invalid(); }
        if (uri.getScheme() == null) throw invalid();
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme)) throw unsupported();
        if (uri.getHost() == null || uri.getHost().isBlank()) throw invalid();
        if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != (scheme.equals("https") ? 443 : 80))) throw unsupported();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String plainHost = host.replace("[", "").replace("]", "");
        if (plainHost.contains("%") || plainHost.equals("localhost") || plainHost.endsWith(".localhost")
            || plainHost.endsWith(".local") || plainHost.endsWith(".internal")) throw unsupported();
        // HTTP clients may bypass a DnsResolver for IP literals, so validate those here too.
        if (plainHost.contains(":") || plainHost.matches("[0-9.]+")) {
            try { if (!isPublic(InetAddress.getByName(plainHost))) throw unsupported(); }
            catch (UnknownHostException e) { throw invalid(); }
        }
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        return URI.create(scheme + "://" + host + (path == null || path.isEmpty() ? "/" : path)
            + (query == null ? "" : "?" + query));
    }

    public URI validateInitial(String url) {
        URI uri = validate(url);
        try { resolve(uri.getHost()); }
        catch (UnknownHostException e) {
            // Unresolvable public-looking hosts fail during background fetching.
        }
        return uri;
    }

    @Override public InetAddress[] resolve(String host) throws UnknownHostException {
        FutureTask<InetAddress[]> task = new FutureTask<>(() -> lookup.resolve(host));
        Thread.ofVirtual().name("argos-dns").start(task);
        InetAddress[] addresses;
        try { addresses = task.get(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) {
            task.cancel(true); Thread.currentThread().interrupt(); throw new UnknownHostException("DNS interrupted");
        } catch (ExecutionException | TimeoutException e) {
            task.cancel(true); throw new UnknownHostException("DNS resolution failed");
        }
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("No addresses");
        for (InetAddress address : addresses) if (!isPublic(address)) throw unsupported();
        // The HTTP connection manager uses exactly these checked addresses, preserving host/SNI.
        return addresses;
    }

    @Override public String resolveCanonicalHostname(String host) { return host; }

    static boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] a = ip.getAddress();
        int x = Byte.toUnsignedInt(a[0]);
        if (a.length == 4) {
            int y = Byte.toUnsignedInt(a[1]);
            return !(x == 0 || x == 10 || x == 127 || x >= 224 || (x == 100 && y >= 64 && y <= 127)
                || (x == 169 && y == 254) || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168)
                || (x == 192 && (y == 0 || y == 2)) || (x == 198 && (y == 18 || y == 19 || y == 51))
                || (x == 203 && y == 0));
        }
        // Only global unicast, excluding documentation, transition/tunnelling and special ranges.
        if (a.length != 16 || (x & 0xe0) != 0x20) return false;
        int second = Byte.toUnsignedInt(a[1]);
        if (x == 0x20 && second == 0x02) return false; // 6to4 embeds IPv4 destinations
        if (x == 0x20 && second == 0x01) {
            int third = Byte.toUnsignedInt(a[2]), fourth = Byte.toUnsignedInt(a[3]);
            if (third < 2 || (third == 0x0d && fourth == 0xb8)) return false;
        }
        return !(x == 0x3f && second == 0xff); // documentation 3fff::/20 (conservative /16)
    }

    static ApiException unsupported() { return new ApiException(422, "UNSUPPORTED_URL", "허용되지 않는 뉴스 URL입니다."); }
    private static ApiException invalid() { return new ApiException(400, "INVALID_URL", "올바른 뉴스 기사 URL을 입력해 주세요."); }
}
