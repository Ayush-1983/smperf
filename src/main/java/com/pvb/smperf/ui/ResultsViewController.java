package com.pvb.smperf.ui;

import com.pvb.smperf.model.ConfigResult;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class ResultsViewController {

    private static final int DEFAULT_NOISE_PERCENT = 5;

    @FXML private LineChart<Number, Number> chart;
    @FXML private TableView<ConfigResult> table;
    @FXML private Label summaryLbl;
    @FXML private Spinner<Integer> noiseSpinner;

    @FXML private TableColumn<ConfigResult, String> colThreads;
    @FXML private TableColumn<ConfigResult, String> colRuns;
    @FXML private TableColumn<ConfigResult, String> colFailed;
    @FXML private TableColumn<ConfigResult, String> colBest;
    @FXML private TableColumn<ConfigResult, String> colMean;
    @FXML private TableColumn<ConfigResult, String> colMax;
    @FXML private TableColumn<ConfigResult, String> colStd;
    @FXML private TableColumn<ConfigResult, String> colNoise;
    @FXML private TableColumn<ConfigResult, String> colUser;
    @FXML private TableColumn<ConfigResult, String> colSys;
    @FXML private TableColumn<ConfigResult, String> colRam;
    @FXML private TableColumn<ConfigResult, String> colSpeedup;
    @FXML private TableColumn<ConfigResult, String> colEff;

    private final ObservableList<ConfigResult> data = FXCollections.observableArrayList();
    private final XYChart.Series<Number, Number> measured = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> ideal = new XYChart.Series<>();
    private int baselineThreads = -1;
    private double noiseThreshold = DEFAULT_NOISE_PERCENT / 100.0;
    private List<ConfigResult> lastResults = List.of();

    @FXML
    private void initialize() {
        table.setItems(data);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No results yet — run a benchmark from the Home tab."));

        setupColumns();
        setupChart();
        setupNoiseControl();
    }

    public void setResults(ObservableList<ConfigResult> results) {
        lastResults = results == null ? List.of() : new ArrayList<>(results);
        data.setAll(lastResults);
        rebuildChart(lastResults);
        updateSummary();
    }

    public void clear() {
        lastResults = List.of();
        data.clear();
        measured.getData().clear();
        ideal.getData().clear();
        baselineThreads = -1;
        updateSummary();
    }

    // ------------------------------------------------------------- noise

    /** Std Dev / Mean of wall time, or NaN when there are too few good runs to say. */
    private static double noise(ConfigResult r) {
        var s = r.stats();
        int ok = s.runs() - s.failed();
        if (ok < 2 || s.wallMean() <= 0) return Double.NaN;
        return s.wallStddev() / s.wallMean();
    }

    private boolean isNoisy(ConfigResult r) {
        double n = noise(r);
        return !Double.isNaN(n) && n > noiseThreshold;
    }

    private void setupNoiseControl() {
        noiseSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 50, DEFAULT_NOISE_PERCENT));
        // commit typed text when the field loses focus
        noiseSpinner.getEditor().focusedProperty().addListener((o, was, focused) -> {
            if (!focused) noiseSpinner.increment(0);
        });
        noiseSpinner.valueProperty().addListener((o, old, v) -> {
            if (v == null) return;
            noiseThreshold = v / 100.0;
            table.refresh();
            rebuildChart(lastResults);
            updateSummary();
        });
    }

    private void updateSummary() {
        if (data.isEmpty()) {
            summaryLbl.setText("No results yet — run a benchmark first.");
            return;
        }
        String base = String.format(Locale.ROOT, "%d %s completed", data.size(),
                data.size() == 1 ? "configuration" : "configurations");
        long noisy = data.stream().filter(this::isNoisy).count();
        summaryLbl.setText(noisy == 0 ? base : String.format(Locale.ROOT,
                "%s — ⚠ %d noisy (above %.0f%%): try more repetitions or close other apps",
                base, noisy, noiseThreshold * 100));
    }

    // ----------------------------------------------------------- columns

    private void setupColumns() {
        colThreads.setCellValueFactory(c -> str(c, r -> String.valueOf(r.stats().threads())));
        colRuns.setCellValueFactory(c -> str(c, r -> r.stats().runs() + "/" + (r.stats().runs() - r.stats().failed())));
        colFailed.setCellValueFactory(c -> str(c, r -> String.valueOf(r.stats().failed())));
        colBest.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallBest())));
        colMean.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallMean())));
        colMax.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallMax())));
        colStd.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallStddev())));
        colNoise.setCellValueFactory(c -> str(c, r -> {
            double n = noise(r);
            return Double.isNaN(n) ? "—" : String.format(Locale.ROOT, "%.1f%%", n * 100);
        }));
        colNoise.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("noisy-cell");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                ConfigResult r = getTableRow() == null ? null : getTableRow().getItem();
                boolean noisy = r != null && isNoisy(r);
                setText(noisy ? "⚠ " + item : item);
                if (noisy) getStyleClass().add("noisy-cell");
            }
        });
        colUser.setCellValueFactory(c -> str(c, r -> fmt(r.stats().userMean())));
        colSys.setCellValueFactory(c -> str(c, r -> fmt(r.stats().sysMean())));
        colRam.setCellValueFactory(c -> str(c, r -> String.format(Locale.ROOT, "%.1f", r.stats().maxRssKb() / 1024.0)));
        colSpeedup.setCellValueFactory(c -> str(c, r ->
                Double.isNaN(r.speedup()) ? "—" : String.format(Locale.ROOT, "%.2f×", r.speedup())));
        colEff.setCellValueFactory(c -> str(c, r ->
                Double.isNaN(r.efficiency()) ? "—" : String.format(Locale.ROOT, "%.1f%%", r.efficiency() * 100)));
    }

    // ------------------------------------------------------------- chart

    private void setupChart() {
        measured.setName("Measured");
        ideal.setName("Ideal scaling");
        chart.getData().setAll(ideal, measured);
        chart.setAnimated(false);
        ((NumberAxis) chart.getXAxis()).setForceZeroInRange(false);
    }

    private void rebuildChart(List<ConfigResult> results) {
        measured.getData().clear();
        ideal.getData().clear();
        baselineThreads = -1;

        if (results == null) return;

        for (ConfigResult r : results) {
            if (Double.isNaN(r.speedup())) continue;
            int t = r.stats().threads();
            if (baselineThreads < 0) baselineThreads = t;

            boolean noisy = isNoisy(r);
            double n = noise(r);

            XYChart.Data<Number, Number> point = new XYChart.Data<>(t, r.speedup());
            point.nodeProperty().addListener((obs, old, node) -> {
                if (node == null) return;
                String tip = String.format(Locale.ROOT, "%d threads → %.2f× speedup (%.0f%% efficiency)",
                        t, r.speedup(), r.efficiency() * 100);
                if (noisy) {
                    tip += String.format(Locale.ROOT, "%n⚠ noisy: Std Dev is %.1f%% of the mean", n * 100);
                    node.getStyleClass().add("noisy-point");
                }
                Tooltip.install(node, new Tooltip(tip));
            });
            measured.getData().add(point);
            ideal.getData().add(new XYChart.Data<>(t, (double) t / baselineThreads));
        }
    }

    // ----------------------------------------------------------- helpers

    private static ReadOnlyStringWrapper str(TableColumn.CellDataFeatures<ConfigResult, String> c,
                                             Function<ConfigResult, String> f) {
        return new ReadOnlyStringWrapper(f.apply(c.getValue()));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}