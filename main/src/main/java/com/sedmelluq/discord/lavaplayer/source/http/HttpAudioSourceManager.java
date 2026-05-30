package com.sedmelluq.discord.lavaplayer.source.http;

import com.sedmelluq.discord.lavaplayer.container.*;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.ProbingAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.*;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.refer;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;
import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.getHeaderValue;

/**
 * Audio source manager which implements finding audio files from HTTP addresses.
 */
public class HttpAudioSourceManager extends ProbingAudioSourceManager implements HttpConfigurable {
    private static final String TRACK_DETAILS_WITH_HEADERS_PREFIX = "http-headers-v1:";

    private final HttpInterfaceManager httpInterfaceManager;

    /**
     * Create a new instance with default media container registry.
     */
    public HttpAudioSourceManager() {
        this(MediaContainerRegistry.DEFAULT_REGISTRY);
    }

    /**
     * Create a new instance.
     */
    public HttpAudioSourceManager(MediaContainerRegistry containerRegistry) {
        super(containerRegistry);

        httpInterfaceManager = new ThreadLocalHttpInterfaceManager(
            HttpClientTools
                .createSharedCookiesHttpBuilder()
                .setRedirectStrategy(new HttpClientTools.NoRedirectsStrategy()),
            HttpClientTools.DEFAULT_REQUEST_CONFIG
        );
    }

    @Override
    public String getSourceName() {
        return "http";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        HttpAudioReference httpReference = getAsHttpReferenceWithHeaders(reference);
        if (httpReference == null) {
            return null;
        }

        if (httpReference.containerDescriptor != null) {
            return createTrack(AudioTrackInfoBuilder.create(httpReference, null).build(), httpReference.containerDescriptor,
                httpReference.headers);
        } else {
            return handleLoadResult(detectContainer(httpReference), httpReference.headers);
        }
    }

    @Override
    protected AudioTrack createTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerDescriptor) {
        return createTrack(trackInfo, containerDescriptor, null);
    }

    /**
     * Create an HTTP track with optional request headers.
     */
    protected AudioTrack createTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerDescriptor,
                                     Map<String, String> headers) {

        return new HttpAudioTrack(trackInfo, containerDescriptor, this, headers);
    }

    /**
     * @return Get an HTTP interface for a playing track.
     */
    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }

    public static AudioReference getAsHttpReference(AudioReference reference) {
        if (reference.identifier == null) {
            return null;
        }

        Map<String, String> headers = HttpAudioHeaders.getHeaders(reference);

        if (reference.identifier.startsWith("https://") || reference.identifier.startsWith("http://")) {
            return reference;
        } else if (reference.identifier.startsWith("icy://")) {
            return new HttpAudioReference("http://" + reference.identifier.substring(6), reference.title,
                reference.containerDescriptor, headers);
        }
        return null;
    }

    private static HttpAudioReference getAsHttpReferenceWithHeaders(AudioReference reference) {
        AudioReference httpReference = getAsHttpReference(reference);

        if (httpReference == null) {
            return null;
        }

        if (httpReference instanceof HttpAudioReference) {
            return (HttpAudioReference) httpReference;
        }

        return new HttpAudioReference(httpReference, HttpAudioHeaders.getHeaders(reference));
    }

    private static AudioReference withHeaders(AudioReference reference, Map<String, String> headers) {
        Map<String, String> normalizedHeaders = HttpAudioHeaders.normalize(headers);

        if (reference instanceof HttpAudioReference && ((HttpAudioReference) reference).headers.equals(normalizedHeaders)) {
            return reference;
        }

        if (normalizedHeaders.isEmpty()) {
            return reference;
        }

        return new HttpAudioReference(reference, normalizedHeaders);
    }

    private AudioItem handleLoadResult(MediaContainerDetectionResult result, Map<String, String> headers) {
        if (result != null) {
            if (result.isReference()) {
                return withHeaders(result.getReference(), headers);
            } else if (!result.isContainerDetected()) {
                throw new FriendlyException("Unknown file format.", COMMON, null);
            } else if (!result.isSupportedFile()) {
                throw new FriendlyException(result.getUnsupportedReason(), COMMON, null);
            } else {
                return createTrack(result.getTrackInfo(), result.getContainerDescriptor(), headers);
            }
        }

        return null;
    }

    private MediaContainerDetectionResult detectContainer(HttpAudioReference reference) {
        MediaContainerDetectionResult result;

        try (HttpInterface httpInterface = getHttpInterface()) {
            result = detectContainerWithClient(httpInterface, reference);
        } catch (IOException e) {
            throw new FriendlyException("Connecting to the URL failed.", SUSPICIOUS, e);
        }

        return result;
    }

    private MediaContainerDetectionResult detectContainerWithClient(HttpInterface httpInterface, HttpAudioReference reference) throws IOException {
        try {
            URI uri = new URI(reference.identifier);

            try (HttpAudioStream inputStream = new HttpAudioStream(httpInterface, uri,
                Units.CONTENT_LENGTH_UNKNOWN, reference.headers)) {

                int statusCode = inputStream.checkStatusCode();
                String redirectUrl = HttpClientTools.getRedirectLocation(reference.identifier, inputStream.getCurrentResponse());

                if (redirectUrl != null) {
                    return refer(null, new HttpAudioReference(redirectUrl, null, reference.headers));
                } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
                    return null;
                } else if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                    throw new FriendlyException("That URL is not playable.", COMMON, new IllegalStateException("Status code " + statusCode));
                }

                // The file extension is derived from the URL in addition to the Content-Type header, because some servers
                // report an incorrect Content-Type (e.g. audio/mpeg for a FLAC file). Without the extension hint, a content
                // sniffing probe such as MP3 could match the hint and run before the format's actual magic bytes are checked.
                MediaContainerHints hints = MediaContainerHints.from(
                    getHeaderValue(inputStream.getCurrentResponse(), "Content-Type"), getUriFileExtension(uri));
                return new MediaContainerDetection(containerRegistry, reference, inputStream, hints).detectContainer();
            }
        } catch (URISyntaxException e) {
            throw new FriendlyException("Not a valid URL.", COMMON, e);
        }
    }

    private static String getUriFileExtension(URI uri) {
        String path = uri.getPath();

        if (path == null) {
            return null;
        }

        int lastSlashIndex = path.lastIndexOf('/');
        String fileName = lastSlashIndex >= 0 ? path.substring(lastSlashIndex + 1) : path;
        int lastDotIndex = fileName.lastIndexOf('.');

        return lastDotIndex >= 0 && lastDotIndex < fileName.length() - 1 ? fileName.substring(lastDotIndex + 1) : null;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        HttpAudioTrack httpTrack = (HttpAudioTrack) track;
        encodeTrackFactoryWithHeaders(httpTrack.getContainerTrackFactory(), httpTrack.getHeaders(), output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        HttpTrackDetails trackDetails = decodeTrackDetails(input.readUTF());

        if (trackDetails.containerTrackFactory != null) {
            return new HttpAudioTrack(trackInfo, trackDetails.containerTrackFactory, this, trackDetails.headers);
        }

        return null;
    }

    @Override
    public void shutdown() {
        // Nothing to shut down
    }

    private void encodeTrackFactoryWithHeaders(MediaContainerDescriptor factory, Map<String, String> headers,
                                               DataOutput output) throws IOException {

        Map<String, String> normalizedHeaders = HttpAudioHeaders.normalize(headers);

        if (normalizedHeaders.isEmpty()) {
            output.writeUTF(encodeTrackFactoryInfo(factory));
            return;
        }

        ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        DataOutputStream dataOutput = new DataOutputStream(byteOutput);

        dataOutput.writeUTF(encodeTrackFactoryInfo(factory));
        dataOutput.writeInt(normalizedHeaders.size());

        for (Map.Entry<String, String> header : normalizedHeaders.entrySet()) {
            dataOutput.writeUTF(header.getKey());
            dataOutput.writeUTF(header.getValue());
        }

        output.writeUTF(TRACK_DETAILS_WITH_HEADERS_PREFIX +
            Base64.getUrlEncoder().withoutPadding().encodeToString(byteOutput.toByteArray()));
    }

    private HttpTrackDetails decodeTrackDetails(String probeInfo) throws IOException {
        if (!probeInfo.startsWith(TRACK_DETAILS_WITH_HEADERS_PREFIX)) {
            return new HttpTrackDetails(decodeTrackFactoryInfo(probeInfo), null);
        }

        byte[] encodedDetails;

        try {
            encodedDetails = Base64.getUrlDecoder().decode(
                probeInfo.substring(TRACK_DETAILS_WITH_HEADERS_PREFIX.length())
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid HTTP track details.", e);
        }

        DataInputStream dataInput = new DataInputStream(new ByteArrayInputStream(encodedDetails));
        MediaContainerDescriptor containerTrackFactory = decodeTrackFactoryInfo(dataInput.readUTF());
        int headerCount = dataInput.readInt();

        if (headerCount < 0 || headerCount > 1024) {
            throw new IOException("Invalid HTTP header count: " + headerCount);
        }

        Map<String, String> headers = new LinkedHashMap<>(headerCount);

        for (int i = 0; i < headerCount; i++) {
            headers.put(dataInput.readUTF(), dataInput.readUTF());
        }

        return new HttpTrackDetails(containerTrackFactory, headers);
    }

    private static class HttpTrackDetails {
        private final MediaContainerDescriptor containerTrackFactory;
        private final Map<String, String> headers;

        private HttpTrackDetails(MediaContainerDescriptor containerTrackFactory, Map<String, String> headers) {
            this.containerTrackFactory = containerTrackFactory;
            this.headers = HttpAudioHeaders.normalize(headers);
        }
    }
}
