package top.initsnow.edge_tts_android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoicePickerActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_VOICE_SHORT_NAME = "initial_voice_short_name";
    public static final String EXTRA_RESULT_VOICE_SHORT_NAME = "result_voice_short_name";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextInputEditText searchEditText;
    private ListView voiceListView;
    private VoiceRepository voiceRepository;
    private final List<VoiceInfo> voices = new ArrayList<>();
    private final List<VoiceInfo> filteredVoices = new ArrayList<>();
    private ArrayAdapter<VoiceInfo> adapter;
    private String initialVoiceShortName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_picker);

        voiceRepository = VoiceRepository.getInstance(this);
        initialVoiceShortName = getIntent().getStringExtra(EXTRA_INITIAL_VOICE_SHORT_NAME);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        statusView = findViewById(R.id.statusView);
        searchEditText = findViewById(R.id.searchEditText);
        voiceListView = findViewById(R.id.voiceListView);
        MaterialButton refreshButton = findViewById(R.id.refreshVoicesButton);

        toolbar.setNavigationOnClickListener(view -> finish());

        adapter = new ArrayAdapter<VoiceInfo>(this, android.R.layout.simple_list_item_2, android.R.id.text1, filteredVoices) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                TextView title = view.findViewById(android.R.id.text1);
                TextView subtitle = view.findViewById(android.R.id.text2);
                VoiceInfo voice = getItem(position);
                if (voice != null) {
                    title.setText(voice.getFriendlyName().isEmpty() ? voice.getShortName() : voice.getFriendlyName());
                    subtitle.setText(voice.getShortName() + " • " + voice.getLocaleTag() + " • " + voice.getGender());
                    title.setSingleLine(false);
                    subtitle.setSingleLine(false);
                }
                return view;
            }
        };
        voiceListView.setAdapter(adapter);
        voiceListView.setOnItemClickListener((parent, view, position, id) -> {
            VoiceInfo voice = filteredVoices.get(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_RESULT_VOICE_SHORT_NAME, voice.getShortName());
            setResult(Activity.RESULT_OK, result);
            finish();
        });

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterVoices(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        refreshButton.setOnClickListener(view -> loadVoices(true));
        loadVoices(false);
    }

    @Override
    protected void onDestroy() {
        executorService.shutdownNow();
        super.onDestroy();
    }

    private void loadVoices(boolean forceRefresh) {
        setStatus(getString(R.string.status_loading_voices));
        executorService.execute(() -> {
            try {
                List<VoiceInfo> loadedVoices = voiceRepository.loadVoices(forceRefresh);
                runOnUiThread(() -> bindVoices(loadedVoices, forceRefresh));
            } catch (IOException exception) {
                runOnUiThread(() -> setStatus(
                        getString(R.string.status_error_format, exception.getMessage())
                ));
            }
        });
    }

    private void bindVoices(List<VoiceInfo> loadedVoices, boolean forceRefresh) {
        voices.clear();
        voices.addAll(loadedVoices);
        filterVoices(searchEditText.getText() == null ? "" : searchEditText.getText().toString());
        setStatus("");
    }

    private void filterVoices(String query) {
        filteredVoices.clear();
        String normalized = query.trim().toLowerCase(Locale.US);
        for (VoiceInfo voice : voices) {
            if (normalized.isEmpty() || voice.matchesQuery(normalized)) {
                filteredVoices.add(voice);
            }
        }
        adapter.notifyDataSetChanged();
        if (filteredVoices.isEmpty()) {
            setStatus(getString(R.string.voice_picker_empty));
        } else if (!normalized.isEmpty()) {
            setStatus("");
        }
        if (initialVoiceShortName != null && !initialVoiceShortName.isEmpty()) {
            for (int index = 0; index < filteredVoices.size(); index++) {
                if (initialVoiceShortName.equals(filteredVoices.get(index).getShortName())) {
                    voiceListView.setSelection(index);
                    break;
                }
            }
            initialVoiceShortName = null;
        }
    }

    private void setStatus(String status) {
        if (status == null || status.isEmpty()) {
            statusView.setText("");
            statusView.setVisibility(android.view.View.GONE);
            return;
        }
        statusView.setVisibility(android.view.View.VISIBLE);
        statusView.setText(status);
    }
}
