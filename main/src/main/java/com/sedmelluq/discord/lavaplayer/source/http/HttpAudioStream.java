package com.sedmelluq.discord.lavaplayer.source.http;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import org.apache.http.client.methods.HttpGet;

import java.net.URI;
import java.util.Map;

class HttpAudioStream extends PersistentHttpStream {
    private final Map<String, String> headers;

    HttpAudioStream(HttpInterface httpInterface, URI contentUrl, Long contentLength, Map<String, String> headers) {
        super(httpInterface, contentUrl, contentLength);
        this.headers = HttpAudioHeaders.normalize(headers);
    }

    @Override
    protected HttpGet getConnectRequest() {
        HttpGet request = super.getConnectRequest();
        HttpAudioHeaders.applyToRequest(request, headers);
        return request;
    }
}
