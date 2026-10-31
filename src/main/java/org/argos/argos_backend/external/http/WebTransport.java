package org.argos.argos_backend.external.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public interface WebTransport {
    record Response(int status, String contentType, String location, byte[] body) {}
    Response exchange(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout, int maxBytes);
}
