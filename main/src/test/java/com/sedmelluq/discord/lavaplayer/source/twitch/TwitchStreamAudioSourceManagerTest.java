package com.sedmelluq.discord.lavaplayer.source.twitch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TwitchStreamAudioSourceManagerTest {
    @Test
    void acceptsOnlyCanonicalChannelNames() {
        assertEquals("valid_name", TwitchStreamAudioSourceManager.getChannelIdentifierFromUrl(
            "https://www.twitch.tv/Valid_Name?ref=test"
        ));
        assertNull(TwitchStreamAudioSourceManager.getChannelIdentifierFromUrl(
            "https://twitch.tv/name\"},\"injected\":true"
        ));
        assertNull(TwitchStreamAudioSourceManager.getChannelIdentifierFromUrl(
            "https://twitch.tv/name#fragment"
        ));
    }
}
