package top.initsnow.edge_tts_android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class EngineSettings {
    private static final String KEY_DEFAULT_VOICE = "default_voice";
    private static final String KEY_RATE = "default_rate";
    private static final String KEY_VOLUME = "default_volume";
    private static final String KEY_PITCH = "default_pitch";
    private static final String KEY_TEST_TEXT = "test_text";

    private final SharedPreferences sharedPreferences;

    private EngineSettings(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    public static EngineSettings from(Context context) {
        return new EngineSettings(
                PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
        );
    }

    public String getDefaultVoice() {
        return sharedPreferences.getString(KEY_DEFAULT_VOICE, "");
    }

    public String getDefaultRate() {
        return sharedPreferences.getString(KEY_RATE, "+0%");
    }

    public String getDefaultVolume() {
        return sharedPreferences.getString(KEY_VOLUME, "+0%");
    }

    public String getDefaultPitch() {
        return sharedPreferences.getString(KEY_PITCH, "+0Hz");
    }

    public String getTestText(Context context) {
        return sharedPreferences.getString(
                KEY_TEST_TEXT,
                context.getString(R.string.default_test_text)
        );
    }

    public void save(
            String voice,
            String rate,
            String volume,
            String pitch,
            String testText
    ) {
        sharedPreferences.edit()
                .putString(KEY_DEFAULT_VOICE, voice)
                .putString(KEY_RATE, rate)
                .putString(KEY_VOLUME, volume)
                .putString(KEY_PITCH, pitch)
                .putString(KEY_TEST_TEXT, testText)
                .apply();
    }
}
