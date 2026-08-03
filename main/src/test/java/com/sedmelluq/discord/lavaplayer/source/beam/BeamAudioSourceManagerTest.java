package com.sedmelluq.discord.lavaplayer.source.beam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BeamAudioSourceManagerTest {
    @Test
    void acceptsOnlyPathSafeChannelNames() {
        assertEquals("valid-name", BeamAudioSourceManager.getChannelNameFromUrl(
            "https://mixer.com/valid-name?ref=test"
        ));
        assertNull(BeamAudioSourceManager.getChannelNameFromUrl(
            "https://mixer.com/name#fragment"
        ));
    }
}
