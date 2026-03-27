package top.initsnow.edge_tts_android;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public final class EdgeTtsApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
