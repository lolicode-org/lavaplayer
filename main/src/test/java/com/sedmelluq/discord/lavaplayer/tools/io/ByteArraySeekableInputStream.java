package com.sedmelluq.discord.lavaplayer.tools.io;

import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoProvider;

import java.util.Collections;
import java.util.List;

/**
 * Simple in-memory {@link SeekableInputStream} backed by a byte array, for use in tests.
 */
public class ByteArraySeekableInputStream extends SeekableInputStream {
    private final byte[] data;
    private int position;

    public ByteArraySeekableInputStream(byte[] data) {
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
