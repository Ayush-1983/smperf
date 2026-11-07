package com.pvb.smperf.ui;

import com.pvb.smperf.model.ConfigResult;
import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.session.SavedConfig;
import com.pvb.smperf.session.SessionIO;
import com.pvb.smperf.session.SessionService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** The "Compare" tab: overlay several saved or live runs on one chart + table. */
public final class CompareView extends BorderPane {

    private static final int MAX_RUNS = 6;
    private static final int PALETTE = 6;   // keep in sync with the .cmp-cN rules in style.css
    private static final String[] METRICS = {"Best time (s)", "Speedup (×)", "Efficiency (%)"};
    private static final String[] Y_LABELS = {"Best wall time (s)", "Speedup (×)", "Efficiency (%)"};

    private record Run(String label, Path source, MachineInfo machine, SavedConfig config,
                       List<ConfigResult> rows) {
        Map<Integer, ConfigResult> byThreads() {
            Map<Integer, ConfigResult> m = new HashMap<>();
            for (ConfigResult r : rows) m.put(r.stats().threads(), r);
            return m;
        }

        int baselineThreads() {
            for (ConfigResult r : rows) if (!Double.isNaN(r.speedup())) return r.stats().threads();
            return -1;
        }

        String detail() {
            if (config == null)
                return source != null ? source.getFileName().toString() : "no configuration info";
            String exe = config.executable() == null ? "?" : new File(config.executable()).getName();
            List<String> args = config.args() == null ? List.of() : config.args();
            return args.isEmpty() ? exe : exe + " " + String.join(" ", args);
        }
    }

    private record Row(int threads) {}

    private final SessionService svc;
    private final Workspace ws;
    private final ObservableList<Run> runs = FXCollections.observableArrayList();
    private final ListView<Run> runList = new ListView<>(runs);
    private final ComboBox<String> metricBox = new ComboBox<>(FXCollections.observableArrayList(METRICS));
    private final NumberAxis xAxis = new NumberAxis();
    private final NumberAxis yAxis = new NumberAxis();
    private final LineChart<Number, Number> chart;
    private final TableView<Row> table = new TableView<>();
    private final Label warnLbl = new Label();
    private final MenuButton recentBtn = new MenuButton("Add recent");

    public CompareView(SessionService svc, Workspace ws) {
        this.svc = svc;
        this.ws = ws;
        getStyleClass().add("win-window");
        setPadding(new Insets(10));

        // ---- chart
        xAxis.setLabel("Threads");
        xAxis.setForceZeroInRange(false);
        chart = new LineChart<>(xAxis, yAxis);
        chart.getStyleClass().add("compare-chart");
        chart.setAnimated(false);
        chart.setLegendVisible(true);

        // ---- table
        table.setPlaceholder(new Label("Add runs on the left to compare them."));

        // ---- run list
        runList.getStyleClass().add("cmp-list");
        runList.setPlaceholder(new Label("No runs added yet"));
        runList.setCellFactory(lv -> new RunCell());
        runList.setFocusTraversable(false);

        // ---- toolbar
        Button addFile = new Button("Add file…");
        addFile.setOnAction(e -> addFilesDialog());
        Button addCur = new Button("Add current results");
        addCur.setOnAction(e -> addCurrent());
        Button clear = new Button("Clear all");
        clear.setOnAction(e -> runs.clear());
        metricBox.setValue(METRICS[0]);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(8, addFile, recentBtn, addCur, sp, new Label("Metric:"), metricBox, clear);
        bar.setAlignment(Pos.CENTER_LEFT);

        warnLbl.getStyleClass().add("cmp-warn");
        warnLbl.setWrapText(true);
        warnLbl.setMaxWidth(Double.MAX_VALUE);
        VBox top = new VBox(6, bar, warnLbl);
        top.setPadding(new Insets(0, 0, 8, 0));

        // ---- layout
        GroupBox runsBox = new GroupBox("Runs");
        runsBox.getContent().add(runList);
        VBox.setVgrow(runList, Priority.ALWAYS);
        runsBox.setPrefWidth(270);
        runsBox.setMinWidth(220);
        runsBox.setMaxWidth(270);

        GroupBox chartBox = new GroupBox("Comparison");
        chartBox.getContent().add(chart);
        VBox.setVgrow(chart, Priority.ALWAYS);

        GroupBox tableBox = new GroupBox("Per-thread-count breakdown");
        tableBox.getContent().add(table);
        VBox.setVgrow(table, Priority.ALWAYS);

        SplitPane split = new SplitPane(chartBox, tableBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        split.getStyleClass().add("plain-split");

        setTop(top);
        setLeft(runsBox);
        setCenter(split);
        BorderPane.setMargin(runsBox, new Insets(0, 10, 0, 0));

        // ---- wiring
        runs.addListener((ListChangeListener<Run>) c -> refresh());
        metricBox.valueProperty().addListener((o, a, b) -> refresh());
        svc.recent().items().addListener((ListChangeListener<Path>) c -> rebuildRecent());
        rebuildRecent();
        refresh();
    }

    // ------------------------------------------------------------ adding runs

    private void addFilesDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Add runs to compare");
        Path d = svc.recent().lastDir();
        if (d != null && d.toFile().isDirectory()) fc.setInitialDirectory(d.toFile());
        fc.getExtensionFilters().addAll(
                new ExtensionFilter("SMPerf results (*.json, *.csv)", "*.json", "*.csv"),
                new ExtensionFilter("All files", "*.*"));
        List<File> files = fc.showOpenMultipleDialog(ws.window());
        if (files != null) files.forEach(f -> addFile(f.toPath()));
    }

    private void addFile(Path p) {
        Path src = p.toAbsolutePath().normalize();
        if (runs.stream().anyMatch(r -> src.equals(r.source()))) {
            ws.info(src.getFileName() + " is already in the comparison.");
            return;
        }
        try {
            SessionIO.Loaded l = SessionIO.load(src);
            add(new Run(uniqueLabel(stripExt(src.getFileName().toString())), src,
                    l.machine(), l.config(), SessionIO.toConfigResults(l.results())));
            svc.recent().add(src);
            svc.recent().setLastDir(src.getParent());
        } catch (IOException | RuntimeException e) {
            ws.error("Could not open " + src.getFileName() + ": " + e.getMessage());
        }
    }

    private void addCurrent() {
        if (svc.busy().get()) {
            ws.error("Wait for the running benchmark to finish.");
            return;
        }
        if (svc.results().isEmpty()) {
            ws.error("There are no results loaded to add.");
            return;
        }
        Path f = svc.file().get();
        Path src = f == null ? null : f.toAbsolutePath().normalize();
        if (src != null && runs.stream().anyMatch(r -> src.equals(r.source()))) {
            ws.info(src.getFileName() + " is already in the comparison.");
            return;
        }
        String base = src != null ? stripExt(src.getFileName().toString())
                : svc.origin().get() == SessionService.Origin.LIVE ? "Live run" : "Current results";
        add(new Run(uniqueLabel(base), src, svc.machine().get(), svc.config().get(),
                new ArrayList<>(svc.results())));   // snapshot: later runs won't change it
    }

    private void add(Run r) {
        if (runs.size() >= MAX_RUNS) {
            ws.error("You can compare up to " + MAX_RUNS + " runs at once.");
            return;
        }
        runs.add(r);
    }

    private void rebuildRecent() {
        recentBtn.getItems().clear();
        if (svc.recent().items().isEmpty()) {
            MenuItem none = new MenuItem("(empty)");
            none.setDisable(true);
            recentBtn.getItems().add(none);
            return;
        }
        for (Path p : svc.recent().items()) {
            MenuItem mi = new MenuItem(p.getFileName().toString());
            mi.setOnAction(e -> addFile(p));
            recentBtn.getItems().add(mi);
        }
    }

    private String uniqueLabel(String base) {
        String label = base;
        int n = 2;
        while (true) {
            final String cand = label;
            if (runs.stream().noneMatch(r -> r.label().equals(cand))) return label;
            label = base + " (" + n++ + ")";
        }
    }

    private static String stripExt(String name) {
        return name.replaceFirst("\\.[^.]+$", "");
    }

    // ---------------------------------------------------------------- refresh

    private void refresh() {
        rebuildChart();
        rebuildTable();
        updateWarnings();
    }

    private int metricIndex() {
        return Math.max(0, metricBox.getSelectionModel().getSelectedIndex());
    }

    private static double metric(ConfigResult r, int m) {
        return switch (m) {
            case 0 -> r.stats().allFailed() ? Double.NaN : r.stats().wallBest();
            case 1 -> r.speedup();
            default -> Double.isNaN(r.efficiency()) ? Double.NaN : r.efficiency() * 100;
        };
    }

    private void rebuildChart() {
        final int m = metricIndex();
        yAxis.setLabel(Y_LABELS[m]);

        List<XYChart.Series<Number, Number>> all = new ArrayList<>();
        for (Run run : runs) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            s.setName(run.label());
            for (ConfigResult r : run.rows()) {
                double v = metric(r, m);
                if (Double.isNaN(v)) continue;
                int t = r.stats().threads();
                XYChart.Data<Number, Number> d = new XYChart.Data<>(t, v);
                d.nodeProperty().addListener((obs, old, node) -> {
                    if (node != null) Tooltip.install(node, new Tooltip(String.format(Locale.ROOT,
                            "%s — %d threads%n%s: %.4f", run.label(), t, METRICS[m], v)));
                });
                s.getData().add(d);
            }
            all.add(s);
        }
        chart.getData().setAll(all);
    }

    private void rebuildTable() {
        table.getColumns().clear();

        Set<Integer> threads = new TreeSet<>();
        List<Map<Integer, ConfigResult>> maps = new ArrayList<>();
        for (Run r : runs) {
            Map<Integer, ConfigResult> m = r.byThreads();
            maps.add(m);
            threads.addAll(m.keySet());
        }
        table.getItems().setAll(threads.stream().map(Row::new).toList());

        table.getColumns().add(col("Threads", 70, row -> String.valueOf(row.threads())));

        for (int i = 0; i < runs.size(); i++) {
            final Map<Integer, ConfigResult> mine = maps.get(i);
            TableColumn<Row, String> group = new TableColumn<>(runs.get(i).label());
            group.setSortable(false);

            group.getColumns().add(col("Best (s)", 90, row -> {
                ConfigResult r = mine.get(row.threads());
                return r == null || r.stats().allFailed() ? "—"
                        : String.format(Locale.ROOT, "%.4f", r.stats().wallBest());
            }));
            group.getColumns().add(col("Speedup", 80, row -> {
                ConfigResult r = mine.get(row.threads());
                return r == null || Double.isNaN(r.speedup()) ? "—"
                        : String.format(Locale.ROOT, "%.2f×", r.speedup());
            }));

            if (i > 0) {
                final Map<Integer, ConfigResult> ref = maps.get(0);
                TableColumn<Row, String> d = col("Δ vs " + runs.get(0).label(), 130,
                        row -> deltaText(delta(ref.get(row.threads()), mine.get(row.threads()))));
                d.setCellFactory(c -> new DeltaCell(ref, mine));
                group.getColumns().add(d);
            }
            table.getColumns().add(group);
        }
    }

    /** Relative change of best wall time vs the reference run: negative = faster. */
    private static double delta(ConfigResult ref, ConfigResult other) {
        if (ref == null || other == null || ref.stats().allFailed() || other.stats().allFailed())
            return Double.NaN;
        double a = ref.stats().wallBest();
        return a <= 0 ? Double.NaN : (other.stats().wallBest() - a) / a;
    }

    private static String deltaText(double d) {
        if (Double.isNaN(d)) return "—";
        if (Math.abs(d) < 0.005) return "≈ same";
        return String.format(Locale.ROOT, d < 0 ? "%.1f%% faster" : "%.1f%% slower", Math.abs(d) * 100);
    }

    private void updateWarnings() {
        List<String> w = new ArrayList<>();
        if (runs.size() > 1) {
            Run first = runs.get(0);

            Set<String> models = new LinkedHashSet<>();
            for (Run r : runs)
                if (r.machine() != null && r.machine().model() != null) models.add(r.machine().model());
            if (models.size() > 1)
                w.add("Runs come from different machines, so timings aren't directly comparable.");

            for (Run r : runs) {
                if (r.config() != null && first.config() != null
                        && !Objects.equals(r.config().args(), first.config().args())) {
                    w.add("Runs used different arguments (the input size may differ).");
                    break;
                }
            }

            Set<Integer> bases = new TreeSet<>();
            for (Run r : runs) if (r.baselineThreads() > 0) bases.add(r.baselineThreads());
            if (bases.size() > 1)
                w.add("Speedup baselines differ between runs; use Best time for a fair comparison.");
        }
        warnLbl.setText(w.isEmpty() ? "" : "⚠ " + String.join("   ⚠ ", w));
        warnLbl.setVisible(!w.isEmpty());
        warnLbl.setManaged(!w.isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static TableColumn<Row, String> col(String title, double width, Function<Row, String> f) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }

    private static final class DeltaCell extends TableCell<Row, String> {
        private final Map<Integer, ConfigResult> ref;
        private final Map<Integer, ConfigResult> mine;

        DeltaCell(Map<Integer, ConfigResult> ref, Map<Integer, ConfigResult> mine) {
            this.ref = ref;
            this.mine = mine;
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("cmp-faster", "cmp-slower");
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item);
            Row row = getTableRow() == null ? null : getTableRow().getItem();
            if (row == null) return;
            double d = delta(ref.get(row.threads()), mine.get(row.threads()));
            if (d <= -0.005) getStyleClass().add("cmp-faster");
            else if (d >= 0.005) getStyleClass().add("cmp-slower");
        }
    }

    private final class RunCell extends ListCell<Run> {
        private final Region swatch = new Region();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox box;

        RunCell() {
            swatch.setMinSize(12, 12);
            swatch.setPrefSize(12, 12);
            swatch.setMaxSize(12, 12);
            name.getStyleClass().add("field-label");
            detail.getStyleClass().add("note");
            Button remove = new Button("✕");
            remove.getStyleClass().add("tool-btn");
            remove.setFocusTraversable(false);
            remove.setTooltip(new Tooltip("Remove from comparison"));
            remove.setOnAction(e -> { if (getItem() != null) runs.remove(getItem()); });
            VBox text = new VBox(1, name, detail);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(8, swatch, text, remove);
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Run r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            name.setText(r.label());
            detail.setText(r.detail());
            swatch.getStyleClass().setAll("cmp-swatch", "cmp-c" + (getIndex() % PALETTE));
            setGraphic(box);
        }
    }
}