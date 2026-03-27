package top.initsnow.edge_tts_android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private View statusCard;
    private TextInputEditText selectedVoiceEditText;
    private TextInputEditText rateEditText;
    private TextInputEditText volumeEditText;
    private TextInputEditText pitchEditText;
    private TextInputEditText testTextEditText;

    private VoiceRepository voiceRepository;
    private EngineSettings engineSettings;
    private final List<VoiceInfo> voices = new ArrayList<>();
    private final ActivityResultLauncher<Intent> voicePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                String voiceShortName = result.getData().getStringExtra(
                        VoicePickerActivity.EXTRA_RESULT_VOICE_SHORT_NAME
                );
                if (voiceShortName == null || voiceShortName.isEmpty()) {
                    return;
                }
                engineSettings.save(
                        voiceShortName,
                        textOf(rateEditText, "+0%"),
                        textOf(volumeEditText, "+0%"),
                        textOf(pitchEditText, "+0Hz"),
                        textOf(testTextEditText, getString(R.string.default_test_text))
                );
                bindSelectedVoice(voiceShortName);
            }
    );
    @Nullable
    private TextToSpeech textToSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        voiceRepository = VoiceRepository.getInstance(this);
        engineSettings = EngineSettings.from(this);

        statusView = findViewById(R.id.statusView);
        statusCard = findViewById(R.id.statusCard);
        selectedVoiceEditText = findViewById(R.id.selectedVoiceEditText);
        rateEditText = findViewById(R.id.rateEditText);
        volumeEditText = findViewById(R.id.volumeEditText);
        pitchEditText = findViewById(R.id.pitchEditText);
        testTextEditText = findViewById(R.id.testTextEditText);

        MaterialButton openVoicePickerButton = findViewById(R.id.openVoicePickerButton);
        MaterialButton saveButton = findViewById(R.id.saveSettingsButton);
        MaterialButton testButton = findViewById(R.id.testVoiceButton);
        MaterialButton openSettingsButton = findViewById(R.id.openSystemSettingsButton);

        rateEditText.setText(engineSettings.getDefaultRate());
        volumeEditText.setText(engineSettings.getDefaultVolume());
        pitchEditText.setText(engineSettings.getDefaultPitch());
        testTextEditText.setText(engineSettings.getTestText(this));

        openVoicePickerButton.setOnClickListener(view -> openVoicePicker());
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
                runOnUiThread(() -> bindVoices(loadedVoices, forceRefresh));
            } catch (IOException exception) {
                runOnUiThread(() -> setStatus(getString(
                        R.string.status_error_format,
                        exception.getMessage()
                )));
            }
        });
    }

    private void bindVoices(List<VoiceInfo> loadedVoices, boolean forceRefresh) {
        voices.clear();
        voices.addAll(loadedVoices);
        bindSelectedVoice(engineSettings.getDefaultVoice());
        setStatus("");
    }

    private void saveSettings() {
        engineSettings.save(
                engineSettings.getDefaultVoice(),
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
                String selectedVoiceName = engineSettings.getDefaultVoice();
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

    private void openVoicePicker() {
        Intent intent = new Intent(this, VoicePickerActivity.class);
        intent.putExtra(VoicePickerActivity.EXTRA_INITIAL_VOICE_SHORT_NAME, engineSettings.getDefaultVoice());
        voicePickerLauncher.launch(intent);
    }

    private void bindSelectedVoice(String shortName) {
        VoiceInfo selected = voiceRepository.findByShortName(voices, shortName);
        if (selected == null) {
            selectedVoiceEditText.setText(R.string.voice_not_selected);
            return;
        }
        selectedVoiceEditText.setText(selected.getDisplayName());
    }

    private void setStatus(String status) {
        if (status == null || status.isEmpty()) {
            statusView.setText("");
            statusCard.setVisibility(View.GONE);
            return;
        }
        statusCard.setVisibility(View.VISIBLE);
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
