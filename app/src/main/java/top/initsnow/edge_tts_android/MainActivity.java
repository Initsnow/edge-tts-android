package top.initsnow.edge_tts_android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private AutoCompleteTextView voiceDropdown;
    private TextInputEditText rateEditText;
    private TextInputEditText volumeEditText;
    private TextInputEditText pitchEditText;
    private TextInputEditText testTextEditText;

    private VoiceRepository voiceRepository;
    private EngineSettings engineSettings;
    private final List<VoiceInfo> voices = new ArrayList<>();
    private final Map<String, VoiceInfo> voicesByLabel = new HashMap<>();
    @Nullable
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        voiceRepository = VoiceRepository.getInstance(this);
        engineSettings = EngineSettings.from(this);

        statusView = findViewById(R.id.statusView);
        voiceDropdown = findViewById(R.id.voiceDropdown);
        rateEditText = findViewById(R.id.rateEditText);
        volumeEditText = findViewById(R.id.volumeEditText);
        pitchEditText = findViewById(R.id.pitchEditText);
        testTextEditText = findViewById(R.id.testTextEditText);

        MaterialButton refreshButton = findViewById(R.id.refreshVoicesButton);
        MaterialButton saveButton = findViewById(R.id.saveSettingsButton);
        MaterialButton testButton = findViewById(R.id.testVoiceButton);
        MaterialButton openSettingsButton = findViewById(R.id.openSystemSettingsButton);

        rateEditText.setText(engineSettings.getDefaultRate());
        volumeEditText.setText(engineSettings.getDefaultVolume());
        pitchEditText.setText(engineSettings.getDefaultPitch());
        testTextEditText.setText(engineSettings.getTestText(this));

        refreshButton.setOnClickListener(view -> refreshVoices(true));
        saveButton.setOnClickListener(view -> {
            saveSettings();
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        });
        testButton.setOnClickListener(view -> testVoice());
        openSettingsButton.setOnClickListener(view -> openSystemTtsSettings());

        if (!EdgeTtsNative.isReady()) {
            setStatus(getString(R.string.native_library_missing, EdgeTtsNative.getLoadError()));
            return;
        }

        refreshVoices(false);
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        executorService.shutdownNow();
        super.onDestroy();
    }

    private void refreshVoices(boolean forceRefresh) {
        setStatus(getString(R.string.status_loading_voices));
        executorService.execute(() -> {
            try {
                List<VoiceInfo> loadedVoices = voiceRepository.loadVoices(forceRefresh);
                runOnUiThread(() -> bindVoices(loadedVoices));
            } catch (IOException exception) {
                runOnUiThread(() -> setStatus(getString(
                        R.string.status_error_format,
                        exception.getMessage()
                )));
            }
        });
    }

    private void bindVoices(List<VoiceInfo> loadedVoices) {
        voices.clear();
        voices.addAll(loadedVoices);
        voicesByLabel.clear();
        List<String> labels = new ArrayList<>(loadedVoices.size());
        for (VoiceInfo voice : loadedVoices) {
            String label = voice.getDisplayName();
            labels.add(label);
            voicesByLabel.put(label, voice);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                labels
        );
        voiceDropdown.setAdapter(adapter);

        VoiceInfo selected = voiceRepository.findByShortName(
                loadedVoices,
                engineSettings.getDefaultVoice()
        );
        if (selected == null && !loadedVoices.isEmpty()) {
            selected = loadedVoices.get(0);
        }
        if (selected != null) {
            voiceDropdown.setText(selected.getDisplayName(), false);
        } else {
            voiceDropdown.setHint(R.string.default_voice_missing);
        }
        setStatus(getString(R.string.status_ready_format, loadedVoices.size()));
    }

    private void saveSettings() {
        String voice = resolveSelectedVoice();
        engineSettings.save(
                voice,
                textOf(rateEditText, "+0%"),
                textOf(volumeEditText, "+0%"),
                textOf(pitchEditText, "+0Hz"),
                textOf(testTextEditText, getString(R.string.default_test_text))
        );
    }

    private void testVoice() {
        if (voices.isEmpty()) {
            setStatus(getString(R.string.default_voice_missing));
            return;
        }
        saveSettings();
        setStatus(getString(R.string.status_testing_voice));
        if (textToSpeech != null) {
            try {
                textToSpeech.shutdown();
            } catch (RuntimeException ignored) {
                // Ignore shutdown failures from a previously broken engine instance.
            }
            textToSpeech = null;
        }
        textToSpeech = new TextToSpeech(getApplicationContext(), status -> {
            try {
                if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
                    setStatus(getString(R.string.status_error_format, "TTS init failed"));
                    return;
                }
                String selectedVoiceName = resolveSelectedVoice();
                if (!selectedVoiceName.isEmpty()) {
                    Set<Voice> availableVoices = textToSpeech.getVoices();
                    if (availableVoices != null) {
                        for (Voice voice : availableVoices) {
                            if (voice.getName().equals(selectedVoiceName)) {
                                textToSpeech.setVoice(voice);
                                break;
                            }
                        }
                    }
                }
                textToSpeech.setPitch(parseAndroidPitch(textOf(pitchEditText, "+0Hz")));
                textToSpeech.setSpeechRate(parseAndroidRate(textOf(rateEditText, "+0%")));
                int speakStatus = textToSpeech.speak(
                        textOf(testTextEditText, getString(R.string.default_test_text)),
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "main-activity-preview"
                );
                if (speakStatus != TextToSpeech.SUCCESS) {
                    setStatus(getString(R.string.status_error_format, "Speak request failed"));
                    return;
                }
                setStatus(getString(R.string.test_completed));
            } catch (RuntimeException exception) {
                setStatus(getString(
                        R.string.status_error_format,
                        exception.getClass().getSimpleName()
                ));
                if (textToSpeech != null) {
                    try {
                        textToSpeech.shutdown();
                    } catch (RuntimeException ignored) {
                        // Ignore secondary shutdown failures.
                    }
                    textToSpeech = null;
                }
            }
        }, getPackageName());
    }

    private void openSystemTtsSettings() {
        Intent intent = new Intent("com.android.settings.TTS_SETTINGS");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.system_settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private String resolveSelectedVoice() {
        VoiceInfo voiceInfo = voicesByLabel.get(voiceDropdown.getText().toString());
        return voiceInfo == null ? "" : voiceInfo.getShortName();
    }

    private void setStatus(String status) {
        statusView.setText(status);
    }

    private static String textOf(TextInputEditText editText, String fallback) {
        if (editText.getText() == null) {
            return fallback;
        }
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private static float parseAndroidRate(String value) {
        try {
            int signed = Integer.parseInt(value.replace("%", ""));
            return Math.max(0.1f, (signed + 100) / 100f);
        } catch (NumberFormatException exception) {
            return 1f;
        }
    }

    private static float parseAndroidPitch(String value) {
        try {
            int signed = Integer.parseInt(value.replace("Hz", ""));
            return Math.max(0.1f, (signed + 100) / 100f);
        } catch (NumberFormatException exception) {
            return 1f;
        }
    }
}
