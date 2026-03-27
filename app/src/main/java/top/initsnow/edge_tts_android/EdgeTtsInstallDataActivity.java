package top.initsnow.edge_tts_android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class EdgeTtsInstallDataActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
