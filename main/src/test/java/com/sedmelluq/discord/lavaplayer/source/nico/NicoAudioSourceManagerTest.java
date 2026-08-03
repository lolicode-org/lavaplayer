package com.sedmelluq.discord.lavaplayer.source.nico;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NicoAudioSourceManagerTest {
    @Test
    void acceptsOnlyCanonicalVideoIds() {
        assertEquals("sm123", NicoAudioSourceManager.getVideoIdFromUrl(
            "https://www.nicovideo.jp/watch/sm123?ref=test"
        ));
        assertNull(NicoAudioSourceManager.getVideoIdFromUrl(
            "https://www.nicovideo.jp/watch/??123"
        ));
    }
}
