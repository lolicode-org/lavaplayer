package com.sedmelluq.discord.lavaplayer.source.http;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;

import java.util.Map;

/**
 * HTTP audio reference with request headers that should be used while probing and playing the track.
 */
public class HttpAudioReference extends AudioReference {
    /**
     * Request headers to apply to HTTP requests for this reference.
     */
    public final Map<String, String> headers;

    /**
     * @param identifier The identifier of the HTTP item.
     * @param title      The title of the HTTP item, if known.
     * @param headers    Request headers to apply while probing and playing this item.
     */
    public HttpAudioReference(String identifier, String title, Map<String, String> headers) {
        this(identifier, title, null, headers);
    }

    /**
     * @param identifier          The identifier of the HTTP item.
     * @param title               The title of the HTTP item, if known.
     * @param containerDescriptor Known probe and probe settings of the item to be loaded.
     * @param headers             Request headers to apply while probing and playing this item.
     */
    public HttpAudioReference(String identifier, String title, MediaContainerDescriptor containerDescriptor,
                              Map<String, String> headers) {

        super(identifier, title, containerDescriptor);
        this.headers = HttpAudioHeaders.normalize(headers);
    }

    /**
     * @param reference The reference to copy identifier, title, and container descriptor from.
     * @param headers   Request headers to apply while probing and playing this item.
     */
    public HttpAudioReference(AudioReference reference, Map<String, String> headers) {
        this(reference.identifier, reference.title, reference.containerDescriptor, headers);
    }

    /**
     * @return Request headers to apply while probing and playing this item.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }
}
