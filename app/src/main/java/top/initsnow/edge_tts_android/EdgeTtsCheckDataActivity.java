package top.initsnow.edge_tts_android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

public final class EdgeTtsCheckDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, new Intent());
        finish();
    }
}
