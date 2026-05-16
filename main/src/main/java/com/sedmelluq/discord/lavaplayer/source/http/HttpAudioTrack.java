package com.sedmelluq.discord.lavaplayer.source.http;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;

/**
 * Audio track that handles processing HTTP addresses as audio tracks.
 */
public class HttpAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(HttpAudioTrack.class);

    private final MediaContainerDescriptor containerTrackFactory;
    private final HttpAudioSourceManager sourceManager;
    private final Map<String, String> headers;

    /**
     * @param trackInfo             Track info
     * @param containerTrackFactory Container track factory - contains the probe with its parameters.
     * @param sourceManager         Source manager used to load this track
     */
    public HttpAudioTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerTrackFactory,
                          HttpAudioSourceManager sourceManager) {

        this(trackInfo, containerTrackFactory, sourceManager, null);
    }

    /**
     * @param trackInfo             Track info
     * @param containerTrackFactory Container track factory - contains the probe with its parameters.
     * @param sourceManager         Source manager used to load this track
     * @param headers               Request headers to apply while playing this track
     */
    public HttpAudioTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerTrackFactory,
                          HttpAudioSourceManager sourceManager, Map<String, String> headers) {

        super(trackInfo);

        this.containerTrackFactory = containerTrackFactory;
        this.sourceManager = sourceManager;
        this.headers = HttpAudioHeaders.normalize(headers);
    }

    /**
     * @return The media probe which handles creating a container-specific delegated track for this track.
     */
    public MediaContainerDescriptor getContainerTrackFactory() {
        return containerTrackFactory;
    }

    /**
     * @return Request headers applied while playing this track.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (HttpInterface httpInterface = sourceManager.getHttpInterface()) {
            log.debug("Starting http track from URL: {}", trackInfo.identifier);

            try (HttpAudioStream inputStream = new HttpAudioStream(httpInterface, new URI(trackInfo.identifier),
                Units.CONTENT_LENGTH_UNKNOWN, headers)) {

                processDelegate((InternalAudioTrack) containerTrackFactory.createTrack(trackInfo, inputStream), localExecutor);
            }
        }
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new HttpAudioTrack(trackInfo, containerTrackFactory, sourceManager, headers);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
