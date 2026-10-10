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
    private static final String KEY_BEATRICE_ENABLED = "beatrice_enabled";
    private static final String KEY_BEATRICE_MODEL_PATH = "beatrice_model_path";
    private static final String KEY_BEATRICE_SPEAKER_ID = "beatrice_speaker_id";
    private static final String KEY_BEATRICE_PITCH_SHIFT = "beatrice_pitch_shift";
    private static final String KEY_BEATRICE_FORMANT_SHIFT = "beatrice_formant_shift";

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

    public boolean isBeatriceEnabled() {
        return sharedPreferences.getBoolean(KEY_BEATRICE_ENABLED, false);
    }

    public String getBeatriceModelPath() {
        return sharedPreferences.getString(KEY_BEATRICE_MODEL_PATH, "");
    }

    public int getBeatriceSpeakerId() {
        return sharedPreferences.getInt(KEY_BEATRICE_SPEAKER_ID, 0);
    }

    public String getBeatricePitchShiftString() {
        return sharedPreferences.getString(KEY_BEATRICE_PITCH_SHIFT, "0.0");
    }

    public double getBeatricePitchShift() {
        try {
            return Double.parseDouble(getBeatricePitchShiftString().trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public String getBeatriceFormantShiftString() {
        return sharedPreferences.getString(KEY_BEATRICE_FORMANT_SHIFT, "0.0");
    }

    public double getBeatriceFormantShift() {
        try {
            return Double.parseDouble(getBeatriceFormantShiftString().trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public String buildBeatriceConfigJson() {
        if (!isBeatriceEnabled()) {
            return null;
        }
        String modelPath = getBeatriceModelPath();
        if (modelPath == null || modelPath.trim().isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("enabled", true);
            json.put("model_path", modelPath.trim());
            json.put("speaker_id", getBeatriceSpeakerId());
            json.put("pitch_shift", getBeatricePitchShift());
            json.put("formant_shift", getBeatriceFormantShift());
            return json.toString();
        } catch (org.json.JSONException ignored) {
            return null;
        }
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

    public void saveBeatrice(
            boolean enabled,
            String modelPath,
            int speakerId,
            String pitchShift,
            String formantShift
    ) {
        sharedPreferences.edit()
                .putBoolean(KEY_BEATRICE_ENABLED, enabled)
                .putString(KEY_BEATRICE_MODEL_PATH, modelPath)
                .putInt(KEY_BEATRICE_SPEAKER_ID, speakerId)
                .putString(KEY_BEATRICE_PITCH_SHIFT, pitchShift)
                .putString(KEY_BEATRICE_FORMANT_SHIFT, formantShift)
                .apply();
    }
}
