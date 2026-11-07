package com.pvb.smperf.session;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/** Recent files + last-used folder, remembered between launches. */
public final class RecentFiles {
    private static final int MAX = 8;
    private final Preferences prefs = Preferences.userNodeForPackage(RecentFiles.class);
    private final ObservableList<Path> items = FXCollections.observableArrayList();

    public RecentFiles() {
        for (String s : prefs.get("recent", "").split("\n")) {
            if (s.isBlank()) continue;
            try { Path p = Path.of(s); if (Files.exists(p)) items.add(p); }
            catch (InvalidPathException ignored) { }
        }
    }

    public ObservableList<Path> items() { return items; }

    public void add(Path p) {
        p = p.toAbsolutePath().normalize();
        items.remove(p);
        items.add(0, p);
        while (items.size() > MAX) items.remove(items.size() - 1);
        store();
    }

    public void clear() { items.clear(); store(); }

    public Path lastDir() {
        String s = prefs.get("lastDir", null);
        try { return s == null ? null : Path.of(s); } catch (InvalidPathException e) { return null; }
    }

    public void setLastDir(Path d) { if (d != null) prefs.put("lastDir", d.toString()); }

    private void store() {
        prefs.put("recent", items.stream().map(Path::toString).collect(Collectors.joining("\n")));
    }
}