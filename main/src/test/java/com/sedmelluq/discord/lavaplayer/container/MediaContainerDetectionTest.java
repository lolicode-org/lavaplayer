package com.sedmelluq.discord.lavaplayer.container;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    private static MediaContainerDetectionResult detect(MediaContainerRegistry registry, byte[] data,
                                                        MediaContainerHints hints) {
        AudioReference reference = new AudioReference("test", "test");
        return new MediaContainerDetection(registry, reference, new ByteArraySeekableInputStream(data), hints)
            .detectContainer();
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

    private static final class ByteArraySeekableInputStream extends SeekableInputStream {
        private final byte[] data;
        private int position;

        private ByteArraySeekableInputStream(byte[] data) {
            super(data.length, 0);
            this.data = data;
        }

        @Override
        public long getPosition() {
            return position;
        }

        @Override
        protected void seekHard(long position) {
            this.position = (int) position;
        }

        @Override
        public boolean canSeekHard() {
            return true;
        }

        @Override
        public List<AudioTrackInfoProvider> getTrackInfoProviders() {
            return Collections.emptyList();
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }

            int count = Math.min(len, data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            return count;
        }
    }
}
