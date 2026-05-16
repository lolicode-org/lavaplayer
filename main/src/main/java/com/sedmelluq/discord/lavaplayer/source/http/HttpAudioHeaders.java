package com.sedmelluq.discord.lavaplayer.source.http;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpGet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpAudioHeaders {
    private HttpAudioHeaders() {
    }

    static Map<String, String> normalize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();

            if (name != null && !name.trim().isEmpty() && value != null) {
                normalized.put(name, value);
            }
        }

        if (normalized.isEmpty()) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(normalized);
    }

    static Map<String, String> getHeaders(Object reference) {
        if (reference instanceof HttpAudioReference) {
            return ((HttpAudioReference) reference).headers;
        }

        return Collections.emptyMap();
    }

    static void applyToRequest(HttpGet request, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!HttpHeaders.RANGE.equalsIgnoreCase(header.getKey())) {
                request.setHeader(header.getKey(), header.getValue());
            }
        }
    }
}
