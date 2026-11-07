package dev.syed.tvpilot.tv;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What to watch, pulled apart: title, season, episode, "latest", movie vs show. Pure Java. */
final class Goal {
    String command = "", title = "", pkg;
    Integer season, episode, year;
    boolean latest, resume, movie, show, music;

    private static final String[][] ORD = {{"first", "1"}, {"second", "2"}, {"third", "3"}, {"fourth", "4"}, {"fifth", "5"}, {"sixth", "6"},
            {"seventh", "7"}, {"eighth", "8"}, {"ninth", "9"}, {"tenth", "10"}, {"pehla", "1"}, {"pehle", "1"}, {"pahla", "1"}, {"dusra", "2"}, {"doosra", "2"},
            {"dusre", "2"}, {"teesra", "3"}, {"tisra", "3"}, {"teesre", "3"}, {"chautha", "4"}, {"chauthe", "4"}, {"paanchva", "5"}, {"one", "1"}, {"two", "2"},
            {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"}, {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"}};

    static String ordinals(String c) {
        for (String[] o : ORD) c = c.replaceAll("\\b" + o[0] + "\\s+(season|episode|ep)\\b", o[1] + "th $1").replaceAll("\\b(season|episode|ep)\\s+" + o[0] + "\\b", "$1 " + o[1]);
        return c.replaceAll("\\b(\\d+)(st|nd|rd|th)\\s+(season|episode|ep)\\b", "$3 $1");
    }

    /** title: the words to search (from Brain.query); command: the full sentence (for season/episode words). */
    static Goal of(String command, String title, String pkg) {
        Goal g = new Goal();
        g.command = command == null ? "" : command;
        g.pkg = pkg;
        String c = ordinals(" " + g.command.toLowerCase(Locale.US) + " ");
        Matcher m;
        if ((m = Pattern.compile("\\bs\\s?(\\d{1,2})\\s?e\\s?(\\d{1,3})\\b").matcher(c)).find()) { g.season = Integer.parseInt(m.group(1)); g.episode = Integer.parseInt(m.group(2)); }
        if (g.season == null && (m = Pattern.compile("\\b(season|seasons|series)\\s*(\\d{1,2})\\b").matcher(c)).find()) g.season = Integer.parseInt(m.group(2));
        if (g.episode == null && (m = Pattern.compile("\\b(episode|ep|epi)\\s*(\\d{1,3})\\b").matcher(c)).find()) g.episode = Integer.parseInt(m.group(2));
        g.latest = Pattern.compile("\\b(latest|newest|new|naya|nayi|recent|last)\\s+(episode|ep|video|videos)\\b|\\blatest\\b").matcher(c).find();
        g.resume = Pattern.compile("\\b(resume|continue|where i left|jahan chhoda)\\b").matcher(c).find();
        g.movie = Pattern.compile("\\b(movie|film|picture)\\b").matcher(c).find();
        g.show = g.season != null || g.episode != null || Pattern.compile("\\b(show|series|web series|serial)\\b").matcher(c).find();
        g.music = Pattern.compile("\\b(song|songs|gaana|gaane|music|album|track)\\b").matcher(c).find();
        if ((m = Pattern.compile("\\((19|20)(\\d\\d)\\)|\\b(19[5-9]\\d|20[0-4]\\d)\\b").matcher(c)).find()) {
            String y = m.group(3) != null ? m.group(3) : m.group(1) + m.group(2);
            g.year = Integer.parseInt(y);
        }
        g.title = clean(title == null ? "" : title);
        return g;
    }

    /** Removes the season / episode / latest words from the title. */
    static String clean(String t) {
        String c = ordinals(" " + t.toLowerCase(Locale.US) + " ");
        c = c.replaceAll("\\bs\\s?\\d{1,2}\\s?e\\s?\\d{1,3}\\b", " ")
                .replaceAll("\\b(season|seasons|series)\\s*\\d{1,2}\\b", " ")
                .replaceAll("\\b(episode|ep|epi)\\s*\\d{1,3}\\b", " ")
                .replaceAll("\\b(latest|newest|naya|nayi|new)\\s+(episode|ep|video|videos)\\b", " ")
                .replaceAll("\\b(the )?(latest|newest|recent) (one)?\\b", " ")
                .replaceAll("\\b(movie|film|web series|series|show|serial|ka|ki|ke|of|from)\\b", " ")
                .replaceAll("\\s+", " ").trim();
        c = c.replaceAll("^(and|of|in|on)\\s+", "").replaceAll("\\s+(and|of|in|on)$", "").trim();
        return c.isEmpty() ? t.trim() : c;
    }

    /** "dhruv rathee" -> "Dhruv Rathee" for what's shown and said. */
    static String nice(String q) {
        StringBuilder b = new StringBuilder();
        for (String w : q.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    @Override public String toString() {
        return "\"" + title + "\"" + (season != null ? " S" + season : "") + (episode != null ? " E" + episode : "") + (latest ? " latest" : "")
                + (movie ? " movie" : "") + (show ? " show" : "") + (year != null ? " " + year : "") + (pkg != null ? " in " + pkg : "");
    }
}
