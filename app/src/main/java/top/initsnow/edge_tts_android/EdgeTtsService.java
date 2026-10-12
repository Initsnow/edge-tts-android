package top.initsnow.edge_tts_android;

import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class EdgeTtsService extends TextToSpeechService {
    private static final String TAG = "EdgeTtsService";
    private static final int SAMPLE_RATE_HZ = 24_000;
    private static final int CHANNEL_COUNT = 1;
    private static final int PCM_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static volatile String sLastSynthesisError;

    public static String getLastSynthesisError() {
        return sLastSynthesisError;
    }

    private final AtomicReference<String> currentRequestId = new AtomicReference<>();

    private VoiceRepository voiceRepository;
    private EngineSettings engineSettings;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureDependencies();
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        try {
            return repository().getLanguageStatus(
                    repository().loadVoices(false),
                    lang,
                    country,
                    variant
            );
        } catch (IOException exception) {
            return TextToSpeech.LANG_NOT_SUPPORTED;
        }
    }

    @Override
    protected String[] onGetLanguage() {
        try {
            List<VoiceInfo> voices = repository().loadVoices(false);
            VoiceInfo voice = repository().findBestVoice(
                    voices,
                    settings().getDefaultVoice(),
                    Locale.getDefault().getISO3Language(),
                    safeIso3Country(Locale.getDefault())
            );
            if (voice == null) {
                return new String[] {"eng", "", ""};
            }
            Locale locale = voice.toLocale();
            return new String[] {
                    safeIso3Language(locale),
                    safeIso3Country(locale),
                    locale.getVariant()
            };
        } catch (IOException exception) {
            return new String[] {"eng", "", ""};
        }
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> result = new ArrayList<>();
        try {
            List<VoiceInfo> voices = repository().loadVoices(false);
            Set<String> features = repository().buildVoiceFeatures();
            for (VoiceInfo voice : voices) {
                result.add(new Voice(
                        voice.getShortName(),
                        voice.toLocale(),
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        true,
                        features
                ));
            }
        } catch (IOException ignored) {
            // Return an empty list if voice loading fails.
        }
        return result;
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        try {
            return repository().findByShortName(
                    repository().loadVoices(false),
                    voiceName
            ) != null ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
        } catch (IOException exception) {
            return TextToSpeech.ERROR;
        }
    }

    @Override
    public int onLoadVoice(String voiceName) {
        return onIsValidVoiceName(voiceName);
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        try {
            List<VoiceInfo> voices = repository().loadVoices(false);
            VoiceInfo voice = repository().findBestVoice(
                    voices,
                    settings().getDefaultVoice(),
                    lang,
                    country
            );
            return voice == null ? null : voice.getShortName();
        } catch (IOException exception) {
            return null;
        }
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String requestId = UUID.randomUUID().toString();
        long requestStartMs = SystemClock.elapsedRealtime();
        currentRequestId.set(requestId);
        sLastSynthesisError = null;
        try {
            List<VoiceInfo> voices = repository().loadVoices(false);
            String text = request.getCharSequenceText() == null
                    ? ""
                    : request.getCharSequenceText().toString();
            if (text.trim().isEmpty()) {
                String emptyMsg = "Synthesis request text is empty";
                Log.w(TAG, emptyMsg);
                sLastSynthesisError = emptyMsg;
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
                return;
            }

            String selectedVoice = resolveVoiceName(request, voices);
            String rate = mapPercentage(request.getSpeechRate(), settings().getDefaultRate());
            String pitch = mapPitch(request.getPitch(), settings().getDefaultPitch());
            String volume = settings().getDefaultVolume();
            String beatriceConfigJson = settings().buildBeatriceConfigJson();
            logLatency("request=" + requestId
                    + " onSynthesizeText textChars=" + text.length()
                    + " voice=" + selectedVoice
                    + " beatriceEnabled=" + (beatriceConfigJson != null));

            String error = beatriceConfigJson != null
                    ? EdgeTtsNative.beginSynthesisWithBeatrice(
                            requestId,
                            text,
                            selectedVoice,
                            rate,
                            volume,
                            pitch,
                            beatriceConfigJson
                    )
                    : EdgeTtsNative.beginSynthesis(
                            requestId,
                            text,
                            selectedVoice,
                            rate,
                            volume,
                            pitch
                    );
            if (error != null && !error.isEmpty()) {
                Log.e(TAG, "request=" + requestId + " beginSynthesis error: " + error);
                sLastSynthesisError = error;
                callback.error(TextToSpeech.ERROR_NETWORK);
                return;
            }
            logLatency("request=" + requestId
                    + " beginSynthesis returned in "
                    + (SystemClock.elapsedRealtime() - requestStartMs) + "ms");

            int startStatus = callback.start(SAMPLE_RATE_HZ, PCM_AUDIO_FORMAT, CHANNEL_COUNT);
            if (startStatus != TextToSpeech.SUCCESS) {
                String statusMsg = "Audio callback.start failed with status: " + startStatus;
                Log.e(TAG, "request=" + requestId + " " + statusMsg);
                sLastSynthesisError = statusMsg;
                callback.error(TextToSpeech.ERROR_OUTPUT);
                return;
            }

            int maxBufferSize = Math.max(callback.getMaxBufferSize(), 8192);
            boolean firstChunkReadLogged = false;
            boolean firstAudioAvailableLogged = false;
            while (true) {
                byte[] chunk = EdgeTtsNative.readPcmChunk(requestId, maxBufferSize);
                if (chunk == null || chunk.length == 0) {
                    break;
                }
                if (!firstChunkReadLogged) {
                    firstChunkReadLogged = true;
                    logLatency("request=" + requestId
                            + " first PCM chunk read in "
                            + (SystemClock.elapsedRealtime() - requestStartMs)
                            + "ms bytes=" + chunk.length);
                }
                int audioStatus = callback.audioAvailable(chunk, 0, chunk.length);
                if (audioStatus != TextToSpeech.SUCCESS) {
                    String audioMsg = "Audio callback.audioAvailable failed with status: " + audioStatus;
                    Log.e(TAG, "request=" + requestId + " " + audioMsg);
                    sLastSynthesisError = audioMsg;
                    callback.error(TextToSpeech.ERROR_OUTPUT);
                    return;
                }
                if (!firstAudioAvailableLogged) {
                    firstAudioAvailableLogged = true;
                    logLatency("request=" + requestId
                            + " first audioAvailable in "
                            + (SystemClock.elapsedRealtime() - requestStartMs)
                            + "ms bytes=" + chunk.length);
                }
            }

            String streamError = EdgeTtsNative.getLastError(requestId);
            if (streamError != null && !streamError.isEmpty()) {
                Log.e(TAG, "request=" + requestId + " stream error: " + streamError);
                sLastSynthesisError = streamError;
                callback.error(mapError(streamError));
                return;
            }

            logLatency("request=" + requestId
                    + " synthesis done in "
                    + (SystemClock.elapsedRealtime() - requestStartMs) + "ms");
            callback.done();
        } catch (Exception exception) {
            String exMsg = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            Log.e(TAG, "request=" + requestId + " onSynthesizeText exception: " + exMsg, exception);
            sLastSynthesisError = exMsg;
            callback.error(TextToSpeech.ERROR_NETWORK);
        } finally {
            EdgeTtsNative.stop(requestId);
            currentRequestId.compareAndSet(requestId, null);
        }
    }

    @Override
    protected void onStop() {
        String requestId = currentRequestId.getAndSet(null);
        if (requestId != null) {
            EdgeTtsNative.stop(requestId);
        }
    }

    private void logLatency(String message) {
        if (isDebugLoggingEnabled()) {
            Log.i(TAG, message);
        }
    }

    private boolean isDebugLoggingEnabled() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private String resolveVoiceName(SynthesisRequest request, List<VoiceInfo> voices) {
        String configuredVoice = settings().getDefaultVoice();
        if (configuredVoice != null && !configuredVoice.isEmpty()
                && repository().findByShortName(voices, configuredVoice) != null) {
            return configuredVoice;
        }
        String requestedVoice = request.getVoiceName();
        if (requestedVoice != null && !requestedVoice.isEmpty()
                && repository().findByShortName(voices, requestedVoice) != null) {
            return requestedVoice;
        }
        VoiceInfo resolved = repository().findBestVoice(
                voices,
                settings().getDefaultVoice(),
                request.getLanguage(),
                request.getCountry()
        );
        if (resolved != null) {
            return resolved.getShortName();
        }
        return EngineSettings.DEFAULT_VOICE_NAME;
    }

    private static String mapPercentage(int androidValue, String fallback) {
        if (androidValue <= 0) {
            return fallback;
        }
        int normalized = androidValue - 100;
        return (normalized >= 0 ? "+" : "") + normalized + "%";
    }

    private static String mapPitch(int androidValue, String fallback) {
        if (androidValue <= 0) {
            return fallback;
        }
        int normalized = (androidValue - 100) * 2;
        return (normalized >= 0 ? "+" : "") + normalized + "Hz";
    }

    private static int mapError(String error) {
        String lower = error.toLowerCase(Locale.US);
        if (lower.contains("network") || lower.contains("http") || lower.contains("websocket")) {
            return TextToSpeech.ERROR_NETWORK;
        }
        return TextToSpeech.ERROR_SYNTHESIS;
    }

    private void ensureDependencies() {
        if (voiceRepository == null) {
            voiceRepository = VoiceRepository.getInstance(getApplicationContext());
        }
        if (engineSettings == null) {
            engineSettings = EngineSettings.from(getApplicationContext());
        }
    }

    private VoiceRepository repository() {
        ensureDependencies();
        return voiceRepository;
    }

    private EngineSettings settings() {
        ensureDependencies();
        return engineSettings;
    }

    private static String safeIso3Language(Locale locale) {
        try {
            return locale.getISO3Language();
        } catch (RuntimeException ignored) {
            return "eng";
        }
    }

    private static String safeIso3Country(Locale locale) {
        try {
            return locale.getISO3Country();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
