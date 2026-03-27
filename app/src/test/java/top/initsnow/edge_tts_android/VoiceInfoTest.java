package top.initsnow.edge_tts_android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VoiceInfoTest {
    @Test
    public void parsesVoicePayload() {
        VoiceInfo voice = new VoiceInfo(
                "Microsoft Server Speech Text to Speech Voice (en-US, AvaNeural)",
                "en-US-AvaNeural",
                "en-US",
                "Female",
                "Ava"
        );

        assertEquals("en-US-AvaNeural", voice.getShortName());
        assertEquals("en-US", voice.getLocaleTag());
        assertEquals("Ava", voice.getFriendlyName());
    }

    @Test
    public void matchesIso3Locale() {
        VoiceInfo voice = new VoiceInfo("name", "en-US-AvaNeural", "en-US", "Female", "Ava");
        assertTrue(voice.matchesIso3("eng", "USA"));
    }
}
