package top.initsnow.edge_tts_android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private NestedScrollView mainScrollView;
    private TextView statusView;
    private View statusCard;
    private TextInputEditText selectedVoiceEditText;
    private TextInputEditText rateEditText;
    private TextInputEditText volumeEditText;
    private TextInputEditText pitchEditText;
    private TextInputEditText testTextEditText;

    private MaterialSwitch beatriceSwitch;
    private View beatriceContainer;
    private TextInputEditText beatriceModelPathEditText;
    private TextView beatriceModelInfoText;
    private Spinner beatriceSpeakerSpinner;
    private TextInputEditText beatricePitchShiftEditText;
    private TextInputEditText beatriceFormantShiftEditText;

    private final List<Integer> beatriceSpeakerIds = new ArrayList<>();
    private final List<String> beatriceSpeakerNames = new ArrayList<>();
    private ArrayAdapter<String> beatriceSpeakerAdapter;

    private VoiceRepository voiceRepository;
    private EngineSettings engineSettings;
    private final List<VoiceInfo> voices = new ArrayList<>();

    private final ActivityResultLauncher<Uri> beatriceFolderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) {
                    return;
                }
                String resolvedPath = resolveFolderPath(uri);
                if (resolvedPath != null && !resolvedPath.isEmpty()) {
                    beatriceModelPathEditText.setText(resolvedPath);
                    loadBeatriceModelInfo(resolvedPath, -1);
                } else {
                    Toast.makeText(this, R.string.beatrice_model_invalid, Toast.LENGTH_SHORT).show();
                }
            }
    );
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

        mainScrollView = findViewById(R.id.mainScrollView);
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

        beatriceSwitch = findViewById(R.id.beatriceSwitch);
        beatriceContainer = findViewById(R.id.beatriceContainer);
        beatriceModelPathEditText = findViewById(R.id.beatriceModelPathEditText);
        MaterialButton selectBeatriceModelButton = findViewById(R.id.selectBeatriceModelButton);
        beatriceModelInfoText = findViewById(R.id.beatriceModelInfoText);
        beatriceSpeakerSpinner = findViewById(R.id.beatriceSpeakerSpinner);
        beatricePitchShiftEditText = findViewById(R.id.beatricePitchShiftEditText);
        beatriceFormantShiftEditText = findViewById(R.id.beatriceFormantShiftEditText);

        beatriceSpeakerAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                beatriceSpeakerNames
        );
        beatriceSpeakerSpinner.setAdapter(beatriceSpeakerAdapter);

        if (!EdgeTtsNative.isBeatriceSupported()) {
            beatriceSwitch.setEnabled(false);
            beatriceSwitch.setChecked(false);
            beatriceContainer.setVisibility(View.GONE);
            beatriceModelInfoText.setVisibility(View.VISIBLE);
            beatriceModelInfoText.setText(R.string.beatrice_not_supported);
        } else {
            boolean beatriceEnabled = engineSettings.isBeatriceEnabled();
            beatriceSwitch.setChecked(beatriceEnabled);
            beatriceContainer.setVisibility(beatriceEnabled ? View.VISIBLE : View.GONE);
            beatriceSwitch.setOnCheckedChangeListener((view, isChecked) -> {
                beatriceContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            });

            String modelPath = engineSettings.getBeatriceModelPath();
            beatriceModelPathEditText.setText(modelPath);
            beatricePitchShiftEditText.setText(engineSettings.getBeatricePitchShiftString());
            beatriceFormantShiftEditText.setText(engineSettings.getBeatriceFormantShiftString());

            selectBeatriceModelButton.setOnClickListener(view -> {
                checkStoragePermission();
                beatriceFolderPickerLauncher.launch(null);
            });

            beatriceModelPathEditText.setOnFocusChangeListener((view, hasFocus) -> {
                if (!hasFocus) {
                    String path = textOf(beatriceModelPathEditText, "");
                    loadBeatriceModelInfo(path, engineSettings.getBeatriceSpeakerId());
                }
            });

            if (!modelPath.isEmpty()) {
                loadBeatriceModelInfo(modelPath, engineSettings.getBeatriceSpeakerId());
            }
        }

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
        int selectedSpeakerId = 0;
        int pos = beatriceSpeakerSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < beatriceSpeakerIds.size()) {
            selectedSpeakerId = beatriceSpeakerIds.get(pos);
        }
        engineSettings.saveBeatrice(
                beatriceSwitch.isChecked(),
                textOf(beatriceModelPathEditText, ""),
                selectedSpeakerId,
                textOf(beatricePitchShiftEditText, "0.0"),
                textOf(beatriceFormantShiftEditText, "0.0")
        );
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception exception) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private String resolveFolderPath(Uri uri) {
        if (uri == null) {
            return "";
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    String subPath = docId.substring("primary:".length());
                    File primaryStorage = Environment.getExternalStorageDirectory();
                    return new File(primaryStorage, subPath).getAbsolutePath();
                } else if (docId.contains(":")) {
                    String[] parts = docId.split(":", 2);
                    String storageId = parts[0];
                    String subPath = parts.length > 1 ? parts[1] : "";
                    File secondary = new File("/storage/" + storageId + "/" + subPath);
                    if (secondary.exists()) {
                        return secondary.getAbsolutePath();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String path = uri.getPath();
        if (path != null) {
            if (path.contains("/document/primary:")) {
                String subPath = path.substring(path.indexOf("/document/primary:") + "/document/primary:".length());
                return new File(Environment.getExternalStorageDirectory(), subPath).getAbsolutePath();
            } else if (path.contains("/tree/primary:")) {
                String subPath = path.substring(path.indexOf("/tree/primary:") + "/tree/primary:".length());
                return new File(Environment.getExternalStorageDirectory(), subPath).getAbsolutePath();
            }
            return path;
        }
        return uri.toString();
    }

    private void loadBeatriceModelInfo(String path, int targetSpeakerId) {
        if (path == null || path.trim().isEmpty()) {
            beatriceModelInfoText.setVisibility(View.GONE);
            beatriceSpeakerNames.clear();
            beatriceSpeakerIds.clear();
            beatriceSpeakerAdapter.notifyDataSetChanged();
            return;
        }
        executorService.execute(() -> {
            String infoJson = EdgeTtsNative.getBeatriceModelInfo(path.trim());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (infoJson == null || infoJson.startsWith("ERROR:")) {
                    beatriceModelInfoText.setVisibility(View.VISIBLE);
                    beatriceModelInfoText.setText(getString(
                            R.string.status_error_format,
                            infoJson != null ? infoJson : "Unknown error"
                    ));
                    beatriceSpeakerNames.clear();
                    beatriceSpeakerIds.clear();
                    beatriceSpeakerAdapter.notifyDataSetChanged();
                    return;
                }
                try {
                    JSONObject json = new JSONObject(infoJson);
                    String modelName = json.optString("name", "Unknown");
                    String version = json.optString("version", "");
                    beatriceModelInfoText.setVisibility(View.VISIBLE);
                    beatriceModelInfoText.setText(getString(R.string.beatrice_model_loaded, modelName, version));

                    JSONArray voices = json.optJSONArray("voices");
                    beatriceSpeakerNames.clear();
                    beatriceSpeakerIds.clear();
                    int selectedIndex = 0;
                    if (voices != null) {
                        for (int i = 0; i < voices.length(); i++) {
                            JSONObject voiceObj = voices.getJSONObject(i);
                            int id = voiceObj.getInt("id");
                            String name = voiceObj.optString("name", "Speaker " + id);
                            beatriceSpeakerIds.add(id);
                            beatriceSpeakerNames.add(id + " - " + name);
                            if (id == targetSpeakerId) {
                                selectedIndex = i;
                            }
                        }
                    }
                    beatriceSpeakerAdapter.notifyDataSetChanged();
                    if (!beatriceSpeakerNames.isEmpty()) {
                        beatriceSpeakerSpinner.setSelection(selectedIndex);
                    }
                } catch (JSONException exception) {
                    beatriceModelInfoText.setVisibility(View.VISIBLE);
                    beatriceModelInfoText.setText(getString(R.string.beatrice_model_invalid));
                }
            });
        });
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
                    runOnUiThread(() -> setStatus(getString(R.string.status_error_format, "TTS init failed (" + status + ")")));
                    return;
                }
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        runOnUiThread(() -> setStatus(getString(R.string.status_playing_voice)));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> setStatus(getString(R.string.test_completed)));
                    }

                    @Override
                    public void onError(String utteranceId) {
                        onError(utteranceId, TextToSpeech.ERROR);
                    }

                    @Override
                    public void onError(String utteranceId, int errorCode) {
                        String detail = EdgeTtsService.getLastSynthesisError();
                        String errorText = (detail != null && !detail.isEmpty())
                                ? detail
                                : formatTtsErrorCode(errorCode);
                        runOnUiThread(() -> {
                            setStatus(getString(R.string.status_error_format, errorText));
                            Toast.makeText(MainActivity.this, getString(R.string.status_error_format, errorText), Toast.LENGTH_LONG).show();
                            if (mainScrollView != null) {
                                mainScrollView.smoothScrollTo(0, 0);
                            }
                        });
                    }
                });

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
                    runOnUiThread(() -> setStatus(getString(R.string.status_error_format, "Speak request failed (" + speakStatus + ")")));
                    return;
                }
                runOnUiThread(() -> setStatus(getString(R.string.status_synthesizing)));
            } catch (RuntimeException exception) {
                runOnUiThread(() -> setStatus(getString(
                        R.string.status_error_format,
                        exception.getClass().getSimpleName()
                )));
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

    private static String formatTtsErrorCode(int errorCode) {
        switch (errorCode) {
            case TextToSpeech.ERROR_NETWORK:
                return "Network error (connection rejected or failed)";
            case TextToSpeech.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case TextToSpeech.ERROR_NOT_INSTALLED_YET:
                return "Voice data not installed";
            case TextToSpeech.ERROR_OUTPUT:
                return "Audio output error";
            case TextToSpeech.ERROR_SERVICE:
                return "TTS service error";
            case TextToSpeech.ERROR_SYNTHESIS:
                return "Speech synthesis failed";
            case TextToSpeech.ERROR_INVALID_REQUEST:
                return "Invalid request";
            default:
                return "TTS error code " + errorCode;
        }
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
