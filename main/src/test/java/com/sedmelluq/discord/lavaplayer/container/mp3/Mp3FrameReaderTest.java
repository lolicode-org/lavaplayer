package com.sedmelluq.discord.lavaplayer.container.mp3;

import com.sedmelluq.discord.lavaplayer.tools.io.ByteArraySeekableInputStream;
import org.junit.jupiter.api.Test;

import static com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder.getFrameSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mp3FrameReaderTest {
    // A valid MPEG-1 Layer III, 320 kbps, 44100 Hz, stereo frame header.
    private static final byte[] FRAME_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0xE0, 0x44};

    /**
     * Regression test: some files (e.g. converted by ncmc) have several KB of padding/junk between the end of the ID3
     * tag and the first MP3 frame. The frame scan must look far enough ahead to skip it rather than rejecting the file.
     */
    @Test
    void findsFrameAfterLargePaddingWhenScanDistanceIsSufficient() throws Exception {
        int paddingLength = 3092;
        byte[] data = frameAfterZeroPadding(paddingLength);

        Mp3FrameReader reader = new Mp3FrameReader(new ByteArraySeekableInputStream(data), new byte[4096]);

        assertTrue(reader.scanForFrame(32768, true));
    }

    @Test
    void throwsWhenFrameIsBeyondScanDistance() throws Exception {
        int paddingLength = 3092;
        byte[] data = frameAfterZeroPadding(paddingLength);

        Mp3FrameReader reader = new Mp3FrameReader(new ByteArraySeekableInputStream(data), new byte[4096]);

        // Frame starts past the scan window, so it must report that no frame was found.
        assertThrows(IllegalStateException.class, () -> reader.scanForFrame(paddingLength - 100, true));
    }

    @Test
    void findsValidatedFrameSequenceAfterLargePadding() throws Exception {
        int paddingLength = 3092;
        byte[] data = frameSequenceAfterZeroPadding(paddingLength, 3);

        Mp3FrameReader reader = new Mp3FrameReader(new ByteArraySeekableInputStream(data), new byte[4096]);

        assertTrue(reader.scanForFrame(32768, true, 3));
        assertEquals(paddingLength, reader.getFrameStartPosition());
    }

    @Test
    void skipsFalseFrameHeaderWhenValidationIsRequired() throws Exception {
        int falseFramePosition = 32;
        int realFramePosition = 2048;
        byte[] data = frameSequenceAfterZeroPadding(realFramePosition, 3);
        System.arraycopy(FRAME_HEADER, 0, data, falseFramePosition, FRAME_HEADER.length);

        Mp3FrameReader reader = new Mp3FrameReader(new ByteArraySeekableInputStream(data), new byte[4096]);

        assertTrue(reader.scanForFrame(32768, true, 3));
        assertEquals(realFramePosition, reader.getFrameStartPosition());
    }

    @Test
    void rejectsSingleFalseFrameHeaderWhenValidationIsRequired() throws Exception {
        byte[] data = frameAfterZeroPadding(32);

        Mp3FrameReader reader = new Mp3FrameReader(new ByteArraySeekableInputStream(data), new byte[4096]);

        assertFalse(reader.scanForFrame(data.length, false, 3));
    }

    private static byte[] frameAfterZeroPadding(int paddingLength) {
        byte[] data = new byte[paddingLength + 64];
        System.arraycopy(FRAME_HEADER, 0, data, paddingLength, FRAME_HEADER.length);
        return data;
    }

    private static byte[] frameSequenceAfterZeroPadding(int paddingLength, int frameCount) {
        int frameSize = getFrameSize(FRAME_HEADER, 0);
        byte[] data = new byte[paddingLength + frameSize * frameCount];

        for (int i = 0; i < frameCount; i++) {
            System.arraycopy(FRAME_HEADER, 0, data, paddingLength + frameSize * i, FRAME_HEADER.length);
        }

        return data;
    }
}
