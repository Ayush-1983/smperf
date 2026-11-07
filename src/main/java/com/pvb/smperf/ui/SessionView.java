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

        // top row: what is loaded | save & export.  Below: open + recent files, filling the height.
        GroupBox current = currentCard();
        GroupBox save = saveCard(act);
        HBox.setHgrow(current, Priority.ALWAYS);
        save.setMinWidth(300);
        save.setPrefWidth(300);
        save.setMaxWidth(300);
        HBox top = new HBox(10, current, save);

        GroupBox open = openCard(act);
        VBox.setVgrow(open, Priority.ALWAYS);

        VBox page = new VBox(10, top, open);
        page.setPadding(new Insets(10, 10, 8, 10));

        setContent(page);
        setFitToWidth(true);
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

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

    private GroupBox currentCard() {
        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(6);
        ColumnConstraints k = new ColumnConstraints(100);
        ColumnConstraints v = new ColumnConstraints();
        v.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(k, v);

        String[] names = {"Source", "File", "State", "Results", "Executable", "Arguments",
                "Threads", "Run settings", "Machine"};
        Label[] vals = {source, file, state, count, exe, args, threads, params, machine};
        for (int i = 0; i < names.length; i++) {
            Label n = new Label(names[i]);
            n.getStyleClass().add("key");
            n.setMinWidth(100);
            vals[i].getStyleClass().add("val");
            g.add(n, 0, i);
            g.add(vals[i], 1, i);
        }
        return card("Current session", g);
    }

    private GroupBox saveCard(SessionActions act) {
        Button json = new Button("Save as JSON…");
        json.setOnAction(e -> act.saveJsonDialog());
        Button csv = new Button("Export CSV…");
        csv.setOnAction(e -> act.exportCsvDialog());
        Button clear = new Button("Clear results");
        clear.setOnAction(e -> act.clear());
        for (Button b : List.of(json, csv, clear)) {
            b.setMaxWidth(Double.MAX_VALUE);
            b.disableProperty().bind(svc.unavailable());
        }

        Label note = new Label("JSON keeps the machine, configuration and results, so a run can be "
                + "reviewed or reproduced later. CSV holds the results table only.");
        note.getStyleClass().add("note");
        note.setWrapText(true);
        return card("Save & export", json, csv, clear, note);
    }

    private GroupBox openCard(SessionActions act) {
        Label hint = new Label("Drop a results file here");
        hint.getStyleClass().add("field-label");
        Label sub = new Label(".json restores everything  ·  .csv restores the results table");
        sub.getStyleClass().add("note");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> act.openDialog());
        browse.disableProperty().bind(svc.busy());

        VBox inner = new VBox(8, hint, sub, browse);
        inner.setAlignment(Pos.CENTER);
        StackPane zone = new StackPane(inner);
        zone.getStyleClass().add("drop-zone");
        zone.setMinWidth(320);
        zone.setPrefWidth(420);
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
        list.setMinHeight(80);
        VBox.setVgrow(list, Priority.ALWAYS);
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
        Label tip = new Label("Double-click a recent file to open it.");
        tip.getStyleClass().add("note");

        VBox recent = new VBox(6, recentHead, list, tip);
        HBox.setHgrow(recent, Priority.ALWAYS);

        HBox row = new HBox(10, zone, recent);
        VBox.setVgrow(row, Priority.ALWAYS);
        return card("Open", row);
    }

    private static GroupBox card(String title, Node... body) {
        GroupBox g = new GroupBox(title, body);
        g.setSpacing(10);
        return g;
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