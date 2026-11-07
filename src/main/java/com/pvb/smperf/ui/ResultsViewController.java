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
import java.util.Locale;
import java.util.function.Function;

public class ResultsViewController {

    @FXML private LineChart<Number, Number> chart;
    @FXML private TableView<ConfigResult> table;
    @FXML private Label summaryLbl;

    @FXML private TableColumn<ConfigResult, String> colThreads;
    @FXML private TableColumn<ConfigResult, String> colRuns;
    @FXML private TableColumn<ConfigResult, String> colFailed;
    @FXML private TableColumn<ConfigResult, String> colBest;
    @FXML private TableColumn<ConfigResult, String> colMean;
    @FXML private TableColumn<ConfigResult, String> colMax;
    @FXML private TableColumn<ConfigResult, String> colStd;
    @FXML private TableColumn<ConfigResult, String> colUser;
    @FXML private TableColumn<ConfigResult, String> colSys;
    @FXML private TableColumn<ConfigResult, String> colRam;
    @FXML private TableColumn<ConfigResult, String> colSpeedup;
    @FXML private TableColumn<ConfigResult, String> colEff;

    private final ObservableList<ConfigResult> data = FXCollections.observableArrayList();
    private final XYChart.Series<Number, Number> measured = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> ideal = new XYChart.Series<>();
    private int baselineThreads = -1;

    @FXML
    private void initialize() {
        table.setItems(data);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No results yet — run a benchmark from the Home tab."));

        setupColumns();
        setupChart();
    }

    public void setResults(ObservableList<ConfigResult> results) {
        data.setAll(results);
        rebuildChart(results);

        if (results == null || results.isEmpty()) {
            summaryLbl.setText("No results yet — run a benchmark first.");
        } else {
            summaryLbl.setText(String.format(Locale.ROOT, "%d %s completed", results.size(),
                    results.size() == 1 ? "configuration" : "configurations"));
        }
    }

    public void clear() {
        data.clear();
        measured.getData().clear();
        ideal.getData().clear();
        baselineThreads = -1;
        summaryLbl.setText("No results yet — run a benchmark first.");
    }

    private void setupColumns() {
        colThreads.setCellValueFactory(c -> str(c, r -> String.valueOf(r.stats().threads())));
        colRuns.setCellValueFactory(c -> str(c, r -> r.stats().runs() + "/" + (r.stats().runs() - r.stats().failed())));
        colFailed.setCellValueFactory(c -> str(c, r -> String.valueOf(r.stats().failed())));
        colBest.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallBest())));
        colMean.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallMean())));
        colMax.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallMax())));
        colStd.setCellValueFactory(c -> str(c, r -> fmt(r.stats().wallStddev())));
        colUser.setCellValueFactory(c -> str(c, r -> fmt(r.stats().userMean())));
        colSys.setCellValueFactory(c -> str(c, r -> fmt(r.stats().sysMean())));
        colRam.setCellValueFactory(c -> str(c, r -> String.format(Locale.ROOT, "%.1f", r.stats().maxRssKb() / 1024.0)));
        colSpeedup.setCellValueFactory(c -> str(c, r ->
                Double.isNaN(r.speedup()) ? "—" : String.format(Locale.ROOT, "%.2f×", r.speedup())));
        colEff.setCellValueFactory(c -> str(c, r ->
                Double.isNaN(r.efficiency()) ? "—" : String.format(Locale.ROOT, "%.1f%%", r.efficiency() * 100)));
    }

    private void setupChart() {
        measured.setName("Measured");
        ideal.setName("Ideal scaling");
        chart.getData().setAll(ideal, measured);
        chart.setAnimated(false);
        ((NumberAxis) chart.getXAxis()).setForceZeroInRange(false);
    }

    private void rebuildChart(ObservableList<ConfigResult> results) {
        measured.getData().clear();
        ideal.getData().clear();
        baselineThreads = -1;

        if (results == null) return;

        for (ConfigResult r : results) {
            if (Double.isNaN(r.speedup())) continue;
            int t = r.stats().threads();
            if (baselineThreads < 0) baselineThreads = t;

            XYChart.Data<Number, Number> point = new XYChart.Data<>(t, r.speedup());
            point.nodeProperty().addListener((obs, old, node) -> {
                if (node != null) {
                    Tooltip.install(node, new Tooltip(String.format(Locale.ROOT,
                            "%d threads → %.2f× speedup (%.0f%% efficiency)",
                            t, r.speedup(), r.efficiency() * 100)));
                }
            });
            measured.getData().add(point);
            ideal.getData().add(new XYChart.Data<>(t, (double) t / baselineThreads));
        }
    }

    private static ReadOnlyStringWrapper str(TableColumn.CellDataFeatures<ConfigResult, String> c,
                                             Function<ConfigResult, String> f) {
        return new ReadOnlyStringWrapper(f.apply(c.getValue()));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }
}