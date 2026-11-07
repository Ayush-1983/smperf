package com.pvb.smperf.ui;

import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.session.SavedConfig;
import com.pvb.smperf.session.SessionService;
import javafx.beans.InvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

/** The "Session" tab: what is loaded, save/export, open + recent files. */
public final class SessionView extends ScrollPane {

    private final SessionService svc;
    private final Label source = value(), file = value(), state = value(), count = value(),
            exe = value(), args = value(), threads = value(), params = value(), machine = value();

    public SessionView(SessionService svc, SessionActions act) {
        this.svc = svc;

        Label title = new Label("Session");
        title.getStyleClass().add("section-heading");
        Label sub = new Label("Save, export and reopen benchmark results.");
        sub.getStyleClass().add("muted");
        VBox head = new VBox(4, title, sub);
        head.setMaxWidth(720);

        VBox page = new VBox(18, head, currentCard(), saveCard(act), openCard(act));
        page.setPadding(new Insets(28, 32, 28, 32));
        page.setAlignment(Pos.TOP_CENTER);

        setContent(page);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setStyle("-fx-background-color: transparent;");

        InvalidationListener l = o -> refresh();
        svc.results().addListener(l);
        svc.origin().addListener(l);
        svc.file().addListener(l);
        svc.dirty().addListener(l);
        svc.machine().addListener(l);
        svc.config().addListener(l);
        svc.savedAt().addListener(l);
        refresh();
    }

    // ------------------------------------------------------------ cards

    private VBox currentCard() {
        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(8);
        ColumnConstraints k = new ColumnConstraints(120);
        ColumnConstraints v = new ColumnConstraints();
        v.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(k, v);

        String[] names = {"Source", "File", "State", "Results", "Executable", "Arguments",
                "Threads", "Run settings", "Machine"};
        Label[] vals = {source, file, state, count, exe, args, threads, params, machine};
        for (int i = 0; i < names.length; i++) {
            Label n = new Label(names[i]);
            n.getStyleClass().add("field-label");
            g.add(n, 0, i);
            g.add(vals[i], 1, i);
        }
        return card("Current session", g);
    }

    private VBox saveCard(SessionActions act) {
        Button json = new Button("Save as JSON…");
        json.getStyleClass().add("primary-button");
        json.setOnAction(e -> act.saveJsonDialog());
        Button csv = new Button("Export CSV…");
        csv.setOnAction(e -> act.exportCsvDialog());
        Button clear = new Button("Clear results");
        clear.setOnAction(e -> act.clear());
        for (Button b : List.of(json, csv, clear)) b.disableProperty().bind(svc.unavailable());

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox row = new HBox(10, json, csv, sp, clear);

        Label note = new Label("JSON keeps the machine, configuration and results, so a run can be "
                + "reviewed or reproduced later. CSV holds the results table only and opens in any spreadsheet.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        return card("Save & export", row, note);
    }

    private VBox openCard(SessionActions act) {
        Label hint = new Label("Drop a results file here");
        hint.getStyleClass().add("field-label");
        Label sub = new Label(".json restores everything  ·  .csv restores the results table");
        sub.getStyleClass().add("muted");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> act.openDialog());
        browse.disableProperty().bind(svc.busy());

        VBox inner = new VBox(6, hint, sub, browse);
        inner.setAlignment(Pos.CENTER);
        StackPane zone = new StackPane(inner);
        zone.getStyleClass().add("drop-zone");
        act.installDropTarget(zone);
        zone.setOnDragEntered(e -> { if (!zone.getStyleClass().contains("drop-active")) zone.getStyleClass().add("drop-active"); });
        zone.setOnDragExited(e -> zone.getStyleClass().removeAll("drop-active"));

        Label recentLbl = new Label("Recent files");
        recentLbl.getStyleClass().add("field-label");
        Button clearList = new Button("Clear list");
        clearList.setOnAction(e -> svc.recent().clear());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox recentHead = new HBox(8, recentLbl, sp, clearList);
        recentHead.setAlignment(Pos.CENTER_LEFT);

        ListView<Path> list = new ListView<>(svc.recent().items());
        list.setPrefHeight(150);
        list.setPlaceholder(new Label("No recent files"));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Path p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null
                        : p.getFileName() + (p.getParent() == null ? "" : "      " + p.getParent()));
            }
        });
        list.setOnMouseClicked(e -> {
            Path sel = list.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && sel != null) act.open(sel);
        });
        return card("Open", zone, recentHead, list);
    }

    private static VBox card(String title, Node... body) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        VBox c = new VBox(12, t);
        c.getChildren().addAll(body);
        c.getStyleClass().add("card");
        c.setMaxWidth(720);
        return c;
    }

    private static Label value() {
        Label l = new Label("—");
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    // ---------------------------------------------------------- refresh

    private void refresh() {
        String when = svc.savedAt().get() == null ? "" : " (saved " + pretty(svc.savedAt().get()) + ")";
        source.setText(switch (svc.origin().get()) {
            case NONE -> "Nothing loaded";
            case LIVE -> "Live benchmark run";
            case FILE -> "Opened from file" + when;
        });
        Path f = svc.file().get();
        file.setText(f == null ? "Not saved yet" : f.toString());

        int n = svc.results().size();
        state.setText(n == 0 ? "—" : svc.dirty().get() ? "Unsaved results" : "Saved");
        count.setText(n + (n == 1 ? " configuration" : " configurations"));

        SavedConfig c = svc.config().get();
        exe.setText(c == null || c.executable() == null ? "—" : c.executable());
        args.setText(c == null || c.args() == null || c.args().isEmpty() ? "—" : String.join("   ", c.args()));
        threads.setText(c == null ? "—" : c.threadsLabel());
        params.setText(c == null ? "—" : c.repeats() + " repeats  ·  " + c.warmup() + " warmup  ·  "
                                         + (c.timeoutSec() == 0 ? "no timeout"
                                            : (c.timeoutSec() % 1 == 0 ? String.valueOf((long) c.timeoutSec()) : String.valueOf(c.timeoutSec())) + " s timeout"));

        MachineInfo m = svc.machine().get();
        machine.setText(m == null ? "—" : m.model() + "  ·  " + m.physicalCount()
                                          + " cores / " + m.logicalCount() + " threads");
    }

    private static String pretty(String iso) {
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
        } catch (Exception e) {
            return iso;
        }
    }
}