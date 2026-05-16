package com.sedmelluq.discord.lavaplayer.source.http;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DecodedTrackHolder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpAudioSourceManagerTest {
    private static final AudioTrackInfo TRACK_INFO = new AudioTrackInfo(
        "Title",
        "Author",
        1000L,
        "https://example.com/audio.mp3",
        false,
        "https://example.com/audio.mp3"
    );

    @Test
    void httpReferenceKeepsHeadersImmutableAndNormalized() {
        Map<String, String> inputHeaders = new LinkedHashMap<>();
        inputHeaders.put("Referer", "https://example.com/player");
        inputHeaders.put(" ", "ignored");
        inputHeaders.put("Null-Value", null);

        HttpAudioReference reference = new HttpAudioReference("https://example.com/audio.mp3", null, inputHeaders);
        inputHeaders.put("User-Agent", "changed later");

        assertEquals(Map.of("Referer", "https://example.com/player"), reference.getHeaders());
        assertThrows(UnsupportedOperationException.class, () -> reference.getHeaders().put("X-Test", "value"));
    }

    @Test
    void icyReferenceConversionKeepsHeaders() {
        HttpAudioReference reference = new HttpAudioReference(
            "icy://example.com/live",
            "Live",
            Map.of("User-Agent", "lavaplayer-test")
        );

        AudioReference converted = HttpAudioSourceManager.getAsHttpReference(reference);

        assertInstanceOf(HttpAudioReference.class, converted);
        assertEquals("http://example.com/live", converted.identifier);
        assertEquals(reference.getHeaders(), ((HttpAudioReference) converted).getHeaders());
    }

    @Test
    void encodedHttpTrackPreservesHeaders() throws Exception {
        HttpAudioSourceManager sourceManager = new HttpAudioSourceManager();
        DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();

        try {
            playerManager.registerSourceManager(sourceManager);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Referer", "https://example.com/player");
            headers.put("User-Agent", "lavaplayer-test");

            AudioTrack track = new HttpAudioTrack(TRACK_INFO, mp3Descriptor(), sourceManager, headers);
            track.setPosition(123L);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            playerManager.encodeTrack(new MessageOutput(output), track);

            DecodedTrackHolder holder = playerManager.decodeTrack(
                new MessageInput(new ByteArrayInputStream(output.toByteArray()))
            );

            assertInstanceOf(HttpAudioTrack.class, holder.decodedTrack);
            HttpAudioTrack decoded = (HttpAudioTrack) holder.decodedTrack;

            assertEquals(headers, decoded.getHeaders());
            assertEquals(123L, decoded.getPosition());
        } finally {
            playerManager.shutdown();
        }
    }

    @Test
    void encodedHttpTrackWithoutHeadersRemainsCompact() throws Exception {
        HttpAudioSourceManager sourceManager = new HttpAudioSourceManager();
        DefaultAudioPlayerManager playerManager = new DefaultAudioPlayerManager();

        try {
            playerManager.registerSourceManager(sourceManager);

            AudioTrack track = new HttpAudioTrack(TRACK_INFO, mp3Descriptor(), sourceManager);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            playerManager.encodeTrack(new MessageOutput(output), track);
            DecodedTrackHolder holder = playerManager.decodeTrack(
                new MessageInput(new ByteArrayInputStream(output.toByteArray()))
            );

            assertInstanceOf(HttpAudioTrack.class, holder.decodedTrack);
            assertTrue(((HttpAudioTrack) holder.decodedTrack).getHeaders().isEmpty());
        } finally {
            playerManager.shutdown();
        }
    }

    private static MediaContainerDescriptor mp3Descriptor() {
        return new MediaContainerDescriptor(MediaContainerRegistry.DEFAULT_REGISTRY.find("mp3"), null);
    }
}
