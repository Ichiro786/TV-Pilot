package dev.syed.tvpilot.tv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Set-of-marks for the decision model. Two kinds:
 *  - boxes: the UI tree's elements, each outlined and tagged "m0", "m1"... (apps that expose a tree);
 *  - grid: a labelled 8 x 6 grid over the screen ("A1" top-left ... "F8" bottom-right) for apps that draw everything
 *    themselves (Netflix, Prime Video, YouTube's Cobalt, Kodi). The model answers "which cell holds X" and "which cell
 *    has the focus highlight", and the D-pad does the rest.
 * Drawing goes through Painter (Android Canvas on the TV, java.awt in the tests).
 */
final class Marks {
    private Marks() {}

    interface Painter {
        int width();
        int height();
        void rect(int l, int t, int r, int b, int argb, float stroke);
        void tag(int x, int y, String text, int bgArgb, int fgArgb, float size);
        void line(int x1, int y1, int x2, int y2, int argb, float stroke);
        byte[] jpeg(int quality) throws Exception;
    }

    static final int COLS = 8, ROWS = 6;
    private static final int[] COLORS = {0xFFFF3B30, 0xFF34C759, 0xFF0A84FF, 0xFFFF9F0A, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFFD60A, 0xFFFF375F};

    /** Outlines each node (display coordinates) on the painter (image coordinates). */
    static void boxes(Painter p, List<Ui.Node> nodes, int displayW, int displayH) {
        float sx = p.width() / (float) displayW, sy = p.height() / (float) displayH;
        float size = Math.max(14, p.height() / 34f);
        for (int i = 0; i < nodes.size(); i++) {
            Ui.Node n = nodes.get(i);
            int c = COLORS[i % COLORS.length];
            int l = Math.round(n.l * sx), t = Math.round(n.t * sy), r = Math.round(n.r * sx), b = Math.round(n.b * sy);
            p.rect(l, t, r, b, c, n.focused ? 4f : 2f);
            p.tag(l + 2, t + 2, "m" + i, c, 0xFFFFFFFF, size);
        }
    }

    /** Thin grid lines and a small label in each cell's top-left corner. */
    static void grid(Painter p) {
        int w = p.width(), h = p.height();
        for (int c = 1; c < COLS; c++) p.line(c * w / COLS, 0, c * w / COLS, h, 0x99FFFF00, 1.5f);
        for (int r = 1; r < ROWS; r++) p.line(0, r * h / ROWS, w, r * h / ROWS, 0x99FFFF00, 1.5f);
        float size = Math.max(12, h / 40f);
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) p.tag(c * w / COLS + 2, r * h / ROWS + 2, cell(r, c), 0xCC000000, 0xFFFFFF00, size);
    }

    static String cell(int row, int col) { return "" + (char) ('A' + row) + (col + 1); }

    /** Choice criteria for "which cell": A1..F8 plus none. */
    static Map<String, String> cellCriteria(String noneMeaning) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++)
            m.put(cell(r, c), "grid cell " + cell(r, c) + " (row " + (char) ('A' + r) + " of A-F from the top, column " + (c + 1) + " of 1-8 from the left)");
        m.put("none", noneMeaning);
        return m;
    }

    /** Centre of a cell in display coordinates; null for "none" or junk. */
    static int[] cellCenter(String cell, int displayW, int displayH) {
        if (cell == null || cell.length() < 2 || cell.equals("none")) return null;
        int r = cell.charAt(0) - 'A', c;
        try { c = Integer.parseInt(cell.substring(1)) - 1; } catch (NumberFormatException e) { return null; }
        if (r < 0 || r >= ROWS || c < 0 || c >= COLS) return null;
        return new int[] {(int) ((c + 0.5) * displayW / COLS), (int) ((r + 0.5) * displayH / ROWS)};
    }

    static int[] cellRC(String cell) {
        if (cell == null || cell.length() < 2 || cell.equals("none")) return null;
        try { return new int[] {cell.charAt(0) - 'A', Integer.parseInt(cell.substring(1)) - 1}; } catch (Exception e) { return null; }
    }

    /** Criteria for "which element" over the tree's marks (plus none). */
    static Map<String, String> markCriteria(List<Ui.Node> marks, Ui.Snapshot s, String noneMeaning) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < marks.size(); i++) m.put("m" + i, Ui.describe(marks.get(i), i, s).substring(("m" + i + ": ").length()));
        m.put("none", noneMeaning);
        return m;
    }

    static List<String> lines(List<Ui.Node> marks, Ui.Snapshot s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) out.add(Ui.describe(marks.get(i), i, s));
        return out;
    }
}
