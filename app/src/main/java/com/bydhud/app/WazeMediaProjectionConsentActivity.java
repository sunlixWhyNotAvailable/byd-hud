package com.bydhud.app;

//launches Android screen-capture consent for Waze frame capture without adding app UI.

import android.app.Activity;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.content.Intent;

//keeps the only manual step as the system MediaProjection consent dialog.
public final class WazeMediaProjectionConsentActivity extends Activity {
    private static final int REQUEST_CAPTURE = 7001;
    private boolean launched;

    @Override
    //starts consent once because Android may recreate translucent activities.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (launched) {
            return;
        }
        launched = true;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            AppEventLogger.event(this, "waze_frame_capture consent_failed no_manager");
            finish();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    //hands successful consent to the foreground service and then removes this transient activity.
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            WazeMediaProjectionController.onConsentResult(this, resultCode, data);
            AppEventLogger.event(this, "waze_frame_capture consent_ok");
        } else {
            AppEventLogger.event(this, "waze_frame_capture consent_denied result=" + resultCode);
        }
        moveTaskToBack(true);
        finish();
    }
}
