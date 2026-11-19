package dev.local.essentials;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Small ring buffer for the transient text that the accessibility service
 * sees: toasts, heads-up notifications and similar flash messages.
 *
 * Nothing is written to disk; entries live in memory until the sidebar
 * service stops or the user clears them.
 */
final class Peeker {

    static final int MAX = 120;
    private static final long DEDUPE_MS = 1200;

    static final class Entry {
        final long time;
        final String app;
        final String text;

        Entry(long time, String app, String text) {
            this.time = time;
            this.app = app;
            this.text = text;
        }
    }

    private static final ArrayDeque<Entry> LOG = new ArrayDeque<>();
    private static long lastAt;
    private static String lastText = "";
    static Runnable listener;

    private Peeker() {
    }

    static synchronized void add(long time, String app, String text) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        if (text.equals(lastText) && time - lastAt < DEDUPE_MS) return;
        lastText = text;
        lastAt = time;
        LOG.addFirst(new Entry(time, app == null ? "" : app, text));
        while (LOG.size() > MAX) LOG.removeLast();
        final Runnable cb = listener;
        if (cb != null) cb.run();
    }

    static synchronized List<Entry> snapshot() {
        return new ArrayList<>(LOG);
    }

    static synchronized int size() {
        return LOG.size();
    }

    static synchronized void clear() {
        LOG.clear();
        lastText = "";
        lastAt = 0;
        final Runnable cb = listener;
        if (cb != null) cb.run();
    }
}
