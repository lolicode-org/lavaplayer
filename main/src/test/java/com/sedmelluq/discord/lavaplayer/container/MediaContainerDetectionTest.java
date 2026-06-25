package com.sedmelluq.discord.lavaplayer.container;

import com.sedmelluq.discord.lavaplayer.container.flac.FlacContainerProbe;
import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection.checkNextBytes;
import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.supportedFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaContainerDetectionTest {
    private static final int[] MAGIC = {0x66, 0x4C, 0x61, 0x43}; // "fLaC"

    /**
     * Regression test for content-sniffing probes (e.g. MP3 frame scan) overriding a format with a definitive signature
     * when the server reports a wrong Content-Type. The signature-based probe must win even though the sniffing probe is
     * listed first and is the one that matches the hint.
     */
    @Test
    void signatureProbeWinsOverContentSniffingProbeMatchingHint() {
        MediaContainerRegistry registry = new MediaContainerRegistry(Arrays.asList(
            new AlwaysMatchingSniffingProbe(),
            new MagicSignatureProbe()
        ));

        byte[] data = new byte[2048];
        data[0] = 0x66; data[1] = 0x4C; data[2] = 0x61; data[3] = 0x43;

        // The hint matches only the sniffing probe, mimicking a FLAC file served as "audio/mpeg".
        MediaContainerDetectionResult result = detect(registry, data, MediaContainerHints.from("audio/mpeg", null));

        assertTrue(result.isContainerDetected());
        assertEquals("signature", result.getContainerDescriptor().probe.getName());
    }

    /**
     * The reordering must not stop content-sniffing probes from detecting headerless formats: when no signature matches,
     * the sniffing probe is still consulted and wins.
     */
    @Test
    void contentSniffingProbeStillMatchesWhenNoSignaturePresent() {
        MediaContainerRegistry registry = new MediaContainerRegistry(Arrays.asList(
            new AlwaysMatchingSniffingProbe(),
            new MagicSignatureProbe()
        ));

        byte[] data = new byte[2048]; // no signature bytes

        MediaContainerDetectionResult result = detect(registry, data, MediaContainerHints.from("audio/mpeg", null));

        assertTrue(result.isContainerDetected());
        assertEquals("sniffing", result.getContainerDescriptor().probe.getName());
    }

    @Test
    void flacProbeSkipsLeadingId3TagBeforeContentSniffingProbeMatchingHint() {
        MediaContainerRegistry registry = new MediaContainerRegistry(Arrays.asList(
            new AlwaysMatchingSniffingProbe(),
            new FlacContainerProbe()
        ));

        MediaContainerDetectionResult result = detect(registry, id3WrappedFlac(), MediaContainerHints.from("audio/mpeg", "mp3"));

        assertTrue(result.isContainerDetected());
        assertEquals("flac", result.getContainerDescriptor().probe.getName());
        assertEquals(1000L, result.getTrackInfo().length);
    }

    private static MediaContainerDetectionResult detect(MediaContainerRegistry registry, byte[] data,
                                                        MediaContainerHints hints) {
        AudioReference reference = new AudioReference("test", "test");
        return new MediaContainerDetection(registry, reference, new ByteArraySeekableInputStream(data), hints)
            .detectContainer();
    }

    private static byte[] id3WrappedFlac() {
        byte[] id3Header = new byte[]{
            0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        byte[] flac = minimalFlac();
        byte[] data = new byte[id3Header.length + flac.length];

        System.arraycopy(id3Header, 0, data, 0, id3Header.length);
        System.arraycopy(flac, 0, data, id3Header.length, flac.length);
        return data;
    }

    private static byte[] minimalFlac() {
        byte[] data = new byte[4 + 4 + 34];
        int position = 0;

        data[position++] = 0x66; data[position++] = 0x4C; data[position++] = 0x61; data[position++] = 0x43;
        data[position++] = (byte) 0x80; data[position++] = 0x00; data[position++] = 0x00; data[position++] = 0x22;

        byte[] streamInfo = new byte[34];
        streamInfo[0] = 0x10; streamInfo[1] = 0x00; // Minimum block size: 4096
        streamInfo[2] = 0x10; streamInfo[3] = 0x00; // Maximum block size: 4096

        long packed = (44100L << 44) // Sample rate
            | (1L << 41) // Channel count minus one: stereo
            | (15L << 36) // Bits per sample minus one: 16-bit
            | 44100L; // One second of samples

        for (int i = 0; i < Long.BYTES; i++) {
            streamInfo[10 + i] = (byte) (packed >>> (56 - i * 8));
        }

        System.arraycopy(streamInfo, 0, data, position, streamInfo.length);
        return data;
    }

    private static AudioTrackInfo dummyTrackInfo(String name) {
        return new AudioTrackInfo(name, name, 1000L, name, false, name);
    }

    private static final class MagicSignatureProbe implements MediaContainerProbe {
        @Override
        public String getName() {
            return "signature";
        }

        @Override
        public boolean matchesHints(MediaContainerHints hints) {
            return false;
        }

        @Override
        public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) throws IOException {
            if (!checkNextBytes(inputStream, MAGIC)) {
                return null;
            }

            return supportedFormat(this, null, dummyTrackInfo("signature"));
        }

        @Override
        public AudioTrack createTrack(String parameters, AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
            return null;
        }
    }

    private static final class AlwaysMatchingSniffingProbe implements MediaContainerProbe {
        @Override
        public String getName() {
            return "sniffing";
        }

        @Override
        public boolean matchesHints(MediaContainerHints hints) {
            return hints.present();
        }

        @Override
        public boolean isContentSniffing() {
            return true;
        }

        @Override
        public MediaContainerDetectionResult probe(AudioReference reference, SeekableInputStream inputStream) {
            return supportedFormat(this, null, dummyTrackInfo("sniffing"));
        }

        @Override
        public AudioTrack createTrack(String parameters, AudioTrackInfo trackInfo, SeekableInputStream inputStream) {
            return null;
        }
    }
}
