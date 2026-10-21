package dev.syed.tvpilot.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * See-through screen with the voice bar: used when the TV hands the Assistant button to us (no override running),
 * when "Record through a see-through screen" is on, and as the test from the main screen.
 */
public final class AssistActivity extends Activity implements Pilot.Host {
    private FrameLayout root;
    private boolean started;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs.init(this);
        root = new FrameLayout(this);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!started) {
            started = true;
            root.post(() -> Pilot.get(this).trigger(this));
        }
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        Pilot.get(this).trigger(this);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) return true;
        if (PilotService.isMicKey(code)) {
            if (e.getRepeatCount() == 0) Pilot.get(this).trigger(this);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (Pilot.get(this).hostedBy(this)) Pilot.get(this).cancel();
            if (!Pilot.get(this).showing()) finish();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override public void attach(MicBar bar) {
        int w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                Math.round(w / 1920f * 330), Gravity.BOTTOM);
        root.addView(bar, lp);
    }

    @Override public void detach(MicBar bar) {
        root.removeView(bar);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            // The bar may have moved to the overlay to drive the TV; only a bar still living here is cancelled.
            if (Pilot.get(this).hostedBy(this)) Pilot.get(this).cancel();
            finish();
        }
    }
}
