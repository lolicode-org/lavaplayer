package com.sedmelluq.discord.lavaplayer.container.flac;

import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection.checkNextBytes;

/**
 * Loads either FLAC header information or a FLAC track object from a stream.
 */
public class FlacFileLoader {
    static final int[] FLAC_CC = new int[]{0x66, 0x4C, 0x61, 0x43};
    private static final int[] ID3_TAG = new int[]{0x49, 0x44, 0x33};
    private static final int MAX_ID3_TAGS = 3;

    private final SeekableInputStream inputStream;
    private final DataInput dataInput;

    /**
     * @param inputStream Input stream to read the FLAC data from. This must be positioned right before FLAC FourCC.
     */
    public FlacFileLoader(SeekableInputStream inputStream) {
        this.inputStream = inputStream;
        this.dataInput = new DataInputStream(inputStream);
    }

    /**
     * Read all metadata from a FLAC file. Stream position is at the beginning of the first frame after this call.
     *
     * @return FLAC track information
     * @throws IOException On IO Error
     */
    public FlacTrackInfo parseHeaders() throws IOException {
        if (!seekToFlacHeader(inputStream)) {
            throw new IllegalStateException("Not a FLAC file");
        }

        if (!checkNextBytes(inputStream, FLAC_CC, false)) {
            throw new IllegalStateException("Not a FLAC file");
        }

        FlacTrackInfoBuilder trackInfoBuilder = new FlacTrackInfoBuilder(FlacMetadataReader.readStreamInfoBlock(dataInput));
        readMetadataBlocks(trackInfoBuilder);
        trackInfoBuilder.setFirstFramePosition(inputStream.getPosition());
        return trackInfoBuilder.build();
    }

    /**
     * Initialise a FLAC track stream.
     *
     * @param context Configuration and output information for processing
     * @return The FLAC track stream which can produce frames.
     * @throws IOException On IO error
     */
    public FlacTrackProvider loadTrack(AudioProcessingContext context) throws IOException {
        return new FlacTrackProvider(context, parseHeaders(), inputStream);
    }

    static boolean seekToFlacHeader(SeekableInputStream inputStream) throws IOException {
        long startPosition = inputStream.getPosition();
        DataInputStream tagInput = new DataInputStream(inputStream);

        for (int reads = 0; reads < MAX_ID3_TAGS; reads++) {
            if (!checkNextBytes(inputStream, ID3_TAG)) {
                return checkNextBytes(inputStream, FLAC_CC);
            }

            byte[] header = new byte[10];
            tagInput.readFully(header);

            int majorVersion = header[3] & 0xFF;
            if (majorVersion < 2 || majorVersion > 5) {
                inputStream.seek(startPosition);
                return false;
            }

            int tagsSize = readSyncProofInteger(header, 6);
            inputStream.seek(inputStream.getPosition() + tagsSize);
        }

        throw new IllegalStateException("Read more than 3 ID3v2 blocks, file is possibly invalid");
    }

    private static int readSyncProofInteger(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 21
            | (data[offset + 1] & 0xFF) << 14
            | (data[offset + 2] & 0xFF) << 7
            | (data[offset + 3] & 0xFF);
    }

    private void readMetadataBlocks(FlacTrackInfoBuilder trackInfoBuilder) throws IOException {
        boolean hasMoreBlocks = trackInfoBuilder.getStreamInfo().hasMetadataBlocks;

        while (hasMoreBlocks) {
            hasMoreBlocks = FlacMetadataReader.readMetadataBlock(dataInput, inputStream, trackInfoBuilder);
        }
    }
}
