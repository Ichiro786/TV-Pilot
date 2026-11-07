package dev.syed.tvpilot.tv;

import java.util.List;

/** What the executor needs from a TV. Tv implements it on the real TV; the JVM tests implement it with a simulated one. */
interface Device {
    /** One look at the screen. jpeg is the clean screenshot (at most maxW wide); sig is a 64x36 brightness signature. */
    final class Frame {
        final byte[] jpeg;
        final int displayW, displayH;
        final int[] sig;
        /** The decoded image, kept to draw marks on (Android Bitmap / java.awt image). */
        final Object image;
        final long at = System.currentTimeMillis();
        Frame(byte[] jpeg, int displayW, int displayH, int[] sig, Object image) { this.jpeg = jpeg; this.displayW = displayW; this.displayH = displayH; this.sig = sig; this.image = image; }
    }

    Ui.Snapshot tree() throws Exception;
    Frame capture(int maxW) throws Exception;
    /** A copy of the frame with the tree's marks (boxes != null) or the labelled grid (grid) drawn on it, as JPEG. */
    byte[] annotate(Frame f, List<Ui.Node> boxes, boolean grid) throws Exception;
    void key(int code, int times) throws Exception;
    /** ACTION_CLICK through accessibility; false when that isn't possible here. */
    boolean click(Ui.Node n) throws Exception;
    /** ACTION_FOCUS (input focus) through accessibility; false when not possible. */
    boolean focus(Ui.Node n) throws Exception;
    /** ACTION_SET_TEXT on an editable node; false when not possible. */
    boolean setText(Ui.Node n, String text) throws Exception;
    void tap(int x, int y) throws Exception;
    void typeText(String text) throws Exception;
    String shell(String cmd) throws Exception;
    String foreground();
    String activity();
    int[] displaySize() throws Exception;
    /** Installed app packages that have a TV launcher entry. */
    List<String> installed() throws Exception;
}
