package com.pvb.smperf.session;

import com.pvb.smperf.model.ConfigResult;
import com.pvb.smperf.model.MachineInfo;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Path;

/** Single source of truth for "what is currently loaded and where did it come from". */
public final class SessionService {

    public enum Origin { NONE, LIVE, FILE }

    private final ObservableList<ConfigResult> results;   // the list MainController already owns
    private final ObjectProperty<MachineInfo> machine = new SimpleObjectProperty<>();
    private final ObjectProperty<SavedConfig> config = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> file = new SimpleObjectProperty<>();
    private final StringProperty savedAt = new SimpleStringProperty();
    private final ObjectProperty<Origin> origin = new SimpleObjectProperty<>(Origin.NONE);
    private final BooleanProperty dirty = new SimpleBooleanProperty();   // live results not saved yet
    private final BooleanProperty busy = new SimpleBooleanProperty();    // benchmark running
    private final BooleanBinding unavailable;
    private final RecentFiles recent = new RecentFiles();
    private boolean suppress;

    public SessionService(ObservableList<ConfigResult> results) {
        this.results = results;
        this.unavailable = Bindings.isEmpty(results).or(busy);
        results.addListener((ListChangeListener<ConfigResult>) c -> {
            if (!suppress && origin.get() == Origin.LIVE) dirty.set(!results.isEmpty());
        });
    }

    public ObservableList<ConfigResult> results() { return results; }
    public ObjectProperty<MachineInfo> machine() { return machine; }
    public ObjectProperty<SavedConfig> config() { return config; }
    public ObjectProperty<Path> file() { return file; }
    public StringProperty savedAt() { return savedAt; }
    public ObjectProperty<Origin> origin() { return origin; }
    public BooleanProperty dirty() { return dirty; }
    public BooleanProperty busy() { return busy; }
    /** True when there is nothing to save, or a run is in progress. */
    public BooleanBinding unavailable() { return unavailable; }
    public RecentFiles recent() { return recent; }

    /** Call when a benchmark starts (after the results list has been cleared). */
    public void beginLive(MachineInfo m, SavedConfig c) {
        machine.set(m);
        config.set(c);
        file.set(null);
        savedAt.set(null);
        origin.set(Origin.LIVE);
        dirty.set(false);
    }

    public void saveJson(Path p) throws IOException {
        SessionIO.saveJson(p, machine.get(), config.get(), results);
        file.set(p);
        dirty.set(false);
        recent.add(p);
    }

    public void exportCsv(Path p) throws IOException {
        SessionIO.exportCsv(p, results);   // CSV drops machine/config, so it doesn't clear "unsaved"
        recent.add(p);
    }

    /** Replaces the current results with the file's. Returns the number of configurations. */
    public int open(Path p) throws IOException {
        SessionIO.Loaded l = SessionIO.load(p);
        var rows = SessionIO.toConfigResults(l.results());
        suppress = true;
        try { results.setAll(rows); } finally { suppress = false; }
        machine.set(l.machine());
        config.set(l.config());
        savedAt.set(l.savedAt());
        file.set(p);
        origin.set(Origin.FILE);
        dirty.set(false);
        recent.add(p);
        return rows.size();
    }

    public void clear() {
        suppress = true;
        try { results.clear(); } finally { suppress = false; }
        machine.set(null);
        config.set(null);
        file.set(null);
        savedAt.set(null);
        origin.set(Origin.NONE);
        dirty.set(false);
    }
}