package dev.syed.tvpilot.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/**
 * Takes the remote's Assistant / mic button for TV Pilot, two ways:
 *  1. Key filter: where the TV lets the key reach accessibility (custom or learned keys), it's swallowed outright.
 *  2. Bounce: on Android TV the system sends the Assistant key straight to Google's voice search, before any
 *     accessibility service sees it. So we watch for Google's voice window instead: the moment it appears we press
 *     Back until it has stayed gone for a moment (Google lets go of the remote's mic), then open TV Pilot's bar.
 *     Google flashes for a fraction of a second; then it's ours.
 */
public final class PilotService extends AccessibilityService implements Pilot.Host {
    static volatile PilotService instance;
    /** Set by the main screen: the next mic press (a key, or Google's popup) is learned. */
    static volatile long learnUntil;
    static volatile String lastLearned;
    private static final ArrayDeque<String> KEYS = new ArrayDeque<>();
    private static final ArrayDeque<String> WINDOWS = new ArrayDeque<>();
    private boolean swallowUp, backDown;
    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Bounce state
    private boolean bouncing;
    private long bounceStart, lastSeen, launchedAt, guardUntil;
    private int backs;
    private String bouncePkg;

    static final int[] STANDARD = {KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST};

    static boolean isMicKey(int code) {
        int learned = Prefs.micKey();
        if (learned > 0) return code == learned;
        for (int k : STANDARD) if (k == code) return true;
        return false;
    }

    static boolean isAssistant(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        for (String k : Prefs.KNOWN_ASSISTANTS) if (k.equals(p)) return true;
        return Prefs.learnedAssistants().contains(p);
    }

    static synchronized String recentKeys() {
        return KEYS.isEmpty() ? "none yet" : String.join(", ", KEYS);
    }

    static synchronized String recentWindows() {
        return WINDOWS.isEmpty() ? "none yet" : String.join(", ", WINDOWS);
    }

    private static synchronized void remember(ArrayDeque<String> q, String s) {
        q.remove(s);
        q.addFirst(s);
        while (q.size() > 4) q.removeLast();
    }

    @Override protected void onServiceConnected() {
        Prefs.init(this);
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
        }
        Log.w("mic button service on");
    }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        main.removeCallbacksAndMessages(null);
        bouncing = false;
        try { Pilot.get(this).hostGone(this); } catch (RuntimeException ignored) {}
        MicBar c = circle;
        circle = null;
        if (c != null) detach(c);
        Log.w("mic button service off");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // MARK: - Keys

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0) remember(KEYS, KeyEvent.keyCodeToString(code).replace("KEYCODE_", "") + " (" + code + ")");

        if (learnUntil > System.currentTimeMillis() && code != KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) {
                Prefs.micKey(code);
                learnUntil = 0;
                swallowUp = true;
                lastLearned = "key " + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
                Log.w("mic button learned: " + KeyEvent.keyCodeToString(code));
            }
            return true;
        }
        if (!down && swallowUp && isMicKey(code)) {
            swallowUp = false;
            return true;
        }

        Pilot pilot = Pilot.get(this);
        // Back on the real remote stops the agent. Its own Back presses come from adb (a virtual device) and are let through.
        boolean fromRemote = e.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
        if (code == KeyEvent.KEYCODE_BACK && pilot.acting() && fromRemote) {
            if (!down) { Log.w("stopped with Back"); pilot.cancel(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK && (pilot.showing() || backDown) && !Prefs.viaActivity() && !pilot.acting()) {
            if (down) {
                backDown = true;
            } else {
                backDown = false;
                pilot.cancel();
            }
            return true;
        }

        // The agent's own adb key presses never count as the mic button.
        if (pilot.acting() && !fromRemote && isMicKey(code)) return false;
        if (Prefs.override() && isMicKey(code)) {
            if (down && e.getRepeatCount() == 0) {
                Log.w("mic key " + KeyEvent.keyCodeToString(code) + " caught");
                probeWindows("key " + KeyEvent.keyCodeToString(code));
                openPilot();
            }
            return true;   // Google never sees it
        }
        return false;
    }

    /** Opens the listening bar: in front as a see-through screen (best for the mic), or as an overlay. */
    void openPilot() {
        if (Prefs.viaActivity()) {
            Intent i = new Intent(this, AssistActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(i);
        } else {
            Pilot.get(this).trigger(this);
        }
    }

    // MARK: - Windows: spotting Google's voice popup

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().contentEquals(pkg)) {
            if (pkg == null && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) checkWindows();
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) remember(WINDOWS, pkg.toString());

        if (Pilot.get(this).acting()) return;
        // Learning: the first window that opens after "press the mic button" is the TV's assistant.
        if (learnUntil > System.currentTimeMillis() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !isLauncherLike(pkg.toString())) {
            Prefs.addAssistant(pkg.toString());
            learnUntil = 0;
            lastLearned = "popup " + pkg;
            Log.w("assistant popup learned: " + pkg);
            startBounce(pkg.toString());
            return;
        }
        if (isAssistant(pkg)) startBounce(pkg.toString());
        else checkWindows();
    }

    private static boolean isLauncherLike(String pkg) {
        return pkg.contains("launcher") || pkg.equals("com.android.systemui") || pkg.equals("android")
                || pkg.equals("com.android.tv.settings") || pkg.startsWith("io.freemytv");
    }

    /** Some popups only show up in the window list: look there too. */
    private void checkWindows() {
        if (bouncing || !Prefs.override()) return;
        String pkg = assistantOnScreen();
        if (pkg != null) startBounce(pkg);
    }

    /** The package of an assistant window on screen right now, or null. */
    private String assistantOnScreen() {
        return assistantOnScreen(false);
    }

    /** focusedOnly: only when it's the window Back would go to (so we never close our own bar by mistake). */
    private String assistantOnScreen(boolean focusedOnly) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (focusedOnly && !w.isFocused() && !w.isActive()) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (isAssistant(p)) return p.toString();
            }
        } catch (RuntimeException ignored) {
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && isAssistant(active.getPackageName())) return active.getPackageName().toString();
        return null;
    }

    private void startBounce(String pkg) {
        if (!Prefs.override()) return;
        if (Pilot.get(this).acting()) return;   // the agent is opening apps; never treat them as Google's popup
        long now = SystemClock.uptimeMillis();
        if (bouncing) {
            lastSeen = now;
            return;
        }
        // Google's results popping up again right after we took over: close them, don't reopen ours.
        boolean guard = now < guardUntil;
        bouncing = true;
        bouncePkg = pkg;
        bounceStart = now;
        lastSeen = now;
        backs = 0;
        launchedAt = guard ? -1 : 0;
        Log.w((guard ? "closing Google again: " : "Google's voice popup opened, taking over: ") + pkg);
        if (!guard) probeWindows("popup " + pkg);
        if (!guard) showCircle();
        if (assistantOnScreen(true) != null) {   // first Back right away, no waiting for the next frame
            performGlobalAction(GLOBAL_ACTION_BACK);
            backs++;
        }
        main.postDelayed(bounceTick, 50);
    }

    private final Runnable bounceTick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            String on = assistantOnScreen();
            if (on != null) {
                lastSeen = now;
                if (backs < 12 && assistantOnScreen(true) != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    backs++;
                }
            }
            // Gone for 350 ms (Google has let go of the mic), or we've tried for 3 s: open ours.
            if (now - lastSeen >= 350 || now - bounceStart > 3000) {
                bouncing = false;
                Log.w("Google closed after " + backs + " Back" + (backs == 1 ? "" : "s") + " (" + (now - bounceStart) + " ms)");
                if (launchedAt == 0) {
                    guardUntil = now + 6000;
                    MicBar.circleHandoffUntil = System.currentTimeMillis() + 3000;
                    openPilot();
                    main.postDelayed(PilotService.this::dropCircle, 2500);   // safety net
                } else {
                    dropCircle();
                }
                return;
            }
            main.postDelayed(this, 50);
        }
    };

    // MARK: - The circle that floats in over Google's popup while it's being closed

    private MicBar circle;

    private void showCircle() {
        if (circle != null || Pilot.get(this).showing()) return;
        circle = new MicBar(this);
        if (!attach(circle)) { circle = null; return; }
        circle.showCircle();
    }

    /** The real bar has taken over (it starts round, in the same spot): remove the stand-in. */
    void dropCircle() {
        MicBar c = circle;
        circle = null;
        if (c != null) main.postDelayed(() -> detach(c), 120);
    }

    static void handedOff() {
        PilotService s = instance;
        if (s != null) s.main.post(s::dropCircle);
    }

    @Override public void onInterrupt() {}

    // MARK: - Pilot.Host: the bar as an overlay along the bottom of the screen

    @Override public boolean attach(MicBar bar) {
        if (wm == null) return false;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = Math.round(w / 1920f * 330);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setTitle("TV Pilot voice bar");
        try {
            wm.addView(bar, lp);
            return true;
        } catch (RuntimeException e) {
            Log.w("overlay failed: " + e);
            return false;
        }
    }

    @Override public void detach(MicBar bar) {
        try {
            if (bar.getParent() != null) wm.removeView(bar);
        } catch (RuntimeException ignored) {
        }
    }

    // MARK: - Reading the screen (1.2.0): the whole UI tree in a few ms, with the focused element, for the executor

    /** The app windows' UI trees (plus the on-screen keyboard), without TV Pilot's own windows. Empty when unavailable. */
    Ui.Snapshot snapshot() {
        Ui.Snapshot s = new Ui.Snapshot();
        s.source = "a11y";
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            if (wm != null) { wm.getDefaultDisplay().getRealMetrics(dm); s.width = dm.widthPixels; s.height = dm.heightPixels; }
            List<AccessibilityWindowInfo> ws = getWindows();
            String activePkg = null;
            for (AccessibilityWindowInfo w : ws) {
                int type = w.getType();
                if (type != AccessibilityWindowInfo.TYPE_APPLICATION && type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && type != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p == null || getPackageName().contentEquals(p)) continue;
                if (type == AccessibilityWindowInfo.TYPE_SYSTEM && !"com.android.systemui".contentEquals(p)) continue;
                if (type == AccessibilityWindowInfo.TYPE_APPLICATION && (activePkg == null || w.isActive() || w.isFocused())) activePkg = p.toString();
                walk(root, -1, 0, s);
            }
            if (activePkg != null) s.pkg = activePkg;
            Ui.fillDeep(s);
        } catch (RuntimeException e) {
            Log.w("snapshot: " + e);
        }
        return s;
    }

    private static void walk(AccessibilityNodeInfo n, int parent, int depth, Ui.Snapshot s) {
        if (n == null || depth > 45 || s.nodes.size() > 1800) return;
        Ui.Node x = new Ui.Node();
        x.idx = s.nodes.size();
        x.parent = parent;
        x.depth = depth;
        x.cls = str(n.getClassName());
        x.text = str(n.getText());
        x.desc = str(n.getContentDescription());
        x.resId = str(n.getViewIdResourceName());
        x.pkg = str(n.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 26) x.hint = str(n.getHintText());
        x.clickable = n.isClickable(); x.focusable = n.isFocusable(); x.focused = n.isFocused(); x.selected = n.isSelected();
        x.editable = n.isEditable(); x.scrollable = n.isScrollable(); x.checked = n.isChecked(); x.enabled = n.isEnabled();
        x.visible = n.isVisibleToUser();
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        x.l = r.left; x.t = r.top; x.r = r.right; x.b = r.bottom;
        x.handle = n;
        s.nodes.add(x);
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) walk(n.getChild(i), x.idx, depth + 1, s);
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }

    static boolean act(Ui.Node n, int action, android.os.Bundle args) {
        if (n == null || !(n.handle instanceof AccessibilityNodeInfo)) return false;
        AccessibilityNodeInfo a = (AccessibilityNodeInfo) n.handle;
        try {
            a.refresh();
            return args == null ? a.performAction(action) : a.performAction(action, args);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What's on screen in the moments after the mic button (to find out what draws a white circle). */
    static volatile String lastWindowsAfterMic = "";

    void probeWindows(String why) {
        final long until = SystemClock.uptimeMillis() + 1600;
        final StringBuilder seen = new StringBuilder(why).append(": ");
        final java.util.Set<String> keys = new java.util.HashSet<>();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    for (AccessibilityWindowInfo w : getWindows()) {
                        AccessibilityNodeInfo r = w.getRoot();
                        String p = r == null ? "?" : str(r.getPackageName());
                        android.graphics.Rect b = new android.graphics.Rect();
                        w.getBoundsInScreen(b);
                        String k = p + " type=" + w.getType() + " layer=" + w.getLayer() + " " + b.width() + "x" + b.height() + " '" + str(w.getTitle()) + "'";
                        if (keys.add(k)) seen.append(k).append("; ");
                    }
                } catch (RuntimeException ignored) {}
                if (SystemClock.uptimeMillis() < until) main.postDelayed(this, 120);
                else { lastWindowsAfterMic = seen.toString(); Log.w("windows after mic: " + lastWindowsAfterMic); }
            }
        });
    }

    static Set<String> assistants() {
        Set<String> s = Prefs.learnedAssistants();
        for (String k : Prefs.KNOWN_ASSISTANTS) s.add(k);
        return s;
    }
}
