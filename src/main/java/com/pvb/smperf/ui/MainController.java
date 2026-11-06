package com.pvb.smperf.ui;

import com.pvb.smperf.engine.EngineClient;
import com.pvb.smperf.engine.EngineEvent;
import com.pvb.smperf.engine.EngineLocator;
import com.pvb.smperf.model.ConfigResult;
import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.model.RunConfig;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainController {

    @FXML private BorderPane root;
    @FXML private TextField binaryField;
    @FXML private TextArea argumentsField;
    @FXML private ComboBox<String> threadMode;
    @FXML private TextField threadField;
    @FXML private Spinner<Integer> repeats;
    @FXML private Spinner<Integer> warmup;
    @FXML private TextField timeout;
    @FXML private Button runButton;
    @FXML private Button cancelButton;
    @FXML private Button viewResultsBtn;
    @FXML private Label status;
    @FXML private Label selectedInfo;
    @FXML private Label machineSummaryLbl;
    @FXML private Label footerStatus;

    private EngineClient engine;
    private Task<Void> activeTask;
    private MachineInfo cachedMachineInfo;

    private final ObservableList<ConfigResult> resultData = FXCollections.observableArrayList();
    private EngineEvent.Result baseline;

    private Parent homeRoot;          // the main.fxml content
    private Parent resultsRoot;
    private ResultsViewController resultsController;

    @FXML
    private void initialize() {
        // Spinners need factories
        repeats.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 64, 5));
        warmup.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 1));

        threadMode.setItems(FXCollections.observableArrayList("Automatic", "Manual"));
        threadMode.setValue("Automatic");
        threadField.setDisable(true);
        threadMode.setOnAction(e ->
                threadField.setDisable(!"Manual".equals(threadMode.getValue())));

        argumentsField.setPromptText("--size 1000000\n--threads {threads}");

        homeRoot = root;   // currently showing home

        loadResultsView();

        try {
            engine = new EngineClient(EngineLocator.find());
            loadMachineAsync();
        } catch (Exception ex) {
            status.setText("Engine unavailable");
            footerStatus.setText("Engine unavailable");
            runButton.setDisable(true);
            machineSummaryLbl.setText("Engine not found — topology unavailable");
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void loadResultsView() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pvb/smperf/ui/results_view.fxml"));
            resultsRoot = loader.load();
            resultsController = loader.getController();
            resultsController.setOnBack(this::showHomeView);
        } catch (Exception e) {
            e.printStackTrace();
            resultsRoot = new Label("Failed to load results view");
        }
    }

    @FXML
    private void showResultsView() {
        resultsController.setResults(resultData);   // even if empty → shows empty state
        Stage stage = (Stage) root.getScene().getWindow();
        stage.getScene().setRoot(resultsRoot);
    }

    private void showHomeView() {
        Stage stage = (Stage) resultsRoot.getScene().getWindow();
        stage.getScene().setRoot(homeRoot);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    @FXML
    private void chooseBinary() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select executable to benchmark");
        Window window = root.getScene().getWindow();
        File selected = chooser.showOpenDialog(window);
        if (selected == null) return;

        Path path = selected.toPath().toAbsolutePath();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            showAlert("Invalid executable", "The selected file must be a regular executable file.");
            return;
        }
        binaryField.setText(path.toString());
        selectedInfo.setText("Selected: " + selected.getName());
    }

    @FXML
    private void startBenchmark() {
        if (engine == null || activeTask != null) return;

        RunConfig config;
        try {
            config = createConfig();
        } catch (Exception ex) {
            showAlert("Invalid configuration", ex.getMessage());
            return;
        }

        resultData.clear();
        baseline = null;
        if (resultsController != null) resultsController.clear();

        setRunning(true);
        status.setText("Benchmark initializing…");
        footerStatus.setText("Running…");

        activeTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                engine.run(config, event -> Platform.runLater(() -> handleEvent(event)));
                return null;
            }
        };

        activeTask.setOnSucceeded(e -> {
            setRunning(false);
            status.setText("Benchmark finished");
            footerStatus.setText("Finished · " + resultData.size() + " configurations");
            activeTask = null;
        });
        activeTask.setOnFailed(e -> {
            Throwable error = activeTask.getException();
            setRunning(false);
            status.setText("Benchmark failed");
            footerStatus.setText("Failed");
            showAlert("Engine error", error == null ? "Unknown error" : error.getMessage());
            activeTask = null;
        });
        activeTask.setOnCancelled(e -> {
            setRunning(false);
            status.setText("Benchmark cancelled");
            footerStatus.setText("Cancelled");
            activeTask = null;
        });

        Thread worker = new Thread(activeTask, "benchmark-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void cancelBenchmark() {
        if (engine == null || activeTask == null) return;
        status.setText("Cancellation requested…");
        cancelButton.setDisable(true);
        engine.cancel();
    }
    @FXML
    private void showSpecsWindow() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/pvb/smperf/ui/specs_window.fxml"));
            Parent rootNode = loader.load();
            SpecsWindowController controller = loader.getController();
            if (cachedMachineInfo != null) {
                controller.populate(cachedMachineInfo);
            }

            Stage mainStage = (Stage) root.getScene().getWindow();
            final boolean wasMaximized = mainStage.isMaximized();

            Stage specStage = new Stage();

            // Critical: do NOT use APPLICATION_MODAL and be careful with owner
            specStage.initModality(Modality.WINDOW_MODAL);
            specStage.initOwner(mainStage);          // keep this for proper modality
            specStage.setTitle("System Topology & Architecture Specs");
            specStage.setScene(new Scene(rootNode, 920, 580));
            specStage.setResizable(true);

            // Protect against the minimise / de-maximise bug
            ChangeListener<Boolean> maximisedGuard = (obs, oldVal, newVal) -> {
                if (wasMaximized && !newVal && specStage.isShowing()) {
                    Platform.runLater(() -> mainStage.setMaximized(true));
                }
            };
            mainStage.maximizedProperty().addListener(maximisedGuard);

            specStage.setOnHidden(e -> {
                mainStage.maximizedProperty().removeListener(maximisedGuard);
                if (wasMaximized) {
                    mainStage.setMaximized(true);
                }
                mainStage.toFront();
                mainStage.requestFocus();
            });

            // Extra safety on some Linux WMs
            specStage.setOnShown(e -> {
                if (wasMaximized) {
                    Platform.runLater(() -> {
                        if (!mainStage.isMaximized()) {
                            mainStage.setMaximized(true);
                        }
                    });
                }
            });

            specStage.showAndWait();

        } catch (Exception ex) {
            showAlert("UI Error", "Could not load specs window: " + ex.getMessage());
        }
    }

    @FXML
    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About SMPerf");
        alert.setHeaderText("SMPerf CPU Performance & Scaling Analyzer");
        alert.setContentText("Version 1.0.0\nA clean desktop utility for multi-threaded benchmark profiling.");
        alert.showAndWait();
    }

    @FXML
    private void exitApp() {
        Platform.exit();
    }

    // ------------------------------------------------------------------
    // Engine helpers
    // ------------------------------------------------------------------

    private void loadMachineAsync() {
        Task<MachineInfo> task = new Task<>() {
            @Override
            protected MachineInfo call() throws Exception {
                return engine.fetchMachine();
            }
        };
        task.setOnSucceeded(e -> {
            cachedMachineInfo = task.getValue();
            updateMachineSummary(cachedMachineInfo);
        });
        task.setOnFailed(e -> machineSummaryLbl.setText("Could not detect machine topology"));
        Thread t = new Thread(task, "machine-loader");
        t.setDaemon(true);
        t.start();
    }

    private void updateMachineSummary(MachineInfo m) {
        if (m == null) return;
        double gib = m.ramBytes() / (1024.0 * 1024 * 1024);
        machineSummaryLbl.setText(String.format(Locale.ROOT,
                "%s  ·  %d cores / %d threads  ·  %.1f GiB RAM",
                m.model(), m.physicalCount(), m.logicalCount(), gib));
    }

    private RunConfig createConfig() {
        String path = binaryField.getText().trim();
        if (path.isEmpty()) throw new IllegalArgumentException("Select a target executable first.");
        Path binary = Path.of(path);
        if (!Files.isExecutable(binary)) throw new IllegalArgumentException("Target is not executable.");

        List<Integer> threads = new ArrayList<>();
        if ("Manual".equals(threadMode.getValue())) {
            String input = threadField.getText().trim();
            if (input.isEmpty()) throw new IllegalArgumentException("Enter thread counts.");
            for (String value : input.split(",")) {
                try {
                    int t = Integer.parseInt(value.trim());
                    if (t < 1) throw new NumberFormatException();
                    threads.add(t);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Thread counts must be positive integers separated by commas.");
                }
            }
        }

        List<String> args = new ArrayList<>();
        String text = argumentsField.getText();
        if (text != null && !text.isBlank()) {
            for (String line : text.split("\\R")) {
                if (!line.isEmpty()) args.add(line);
            }
        }

        double timeoutValue;
        try {
            timeoutValue = Double.parseDouble(timeout.getText().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid timeout value.");
        }

        return new RunConfig(binary, args, threads, repeats.getValue(), warmup.getValue(), timeoutValue);
    }

    private void handleEvent(EngineEvent event) {
        if (event instanceof EngineEvent.Result r) {
            ConfigResult result = ConfigResult.of(r, baseline);
            if (!r.allFailed() && baseline == null) {
                baseline = r;
                result = ConfigResult.of(r, baseline);
            }
            resultData.add(result);
            status.setText(String.format(Locale.ROOT, "%d threads completed · %d/%d successful",
                    r.threads(), r.runs() - r.failed(), r.runs()));
        } else if (event instanceof EngineEvent.Failure f) {
            status.setText("Error at " + f.threads() + " threads: " + f.message());
        } else if (event instanceof EngineEvent.Done d) {
            if (d.cancelled()) status.setText("Benchmark cancelled");
            else if (d.ok()) status.setText("All configurations completed");
            else status.setText("Benchmark terminated with errors");
        }
    }

    private void setRunning(boolean running) {
        runButton.setDisable(running);
        cancelButton.setDisable(!running);
        binaryField.setDisable(running);
        argumentsField.setDisable(running);
        threadMode.setDisable(running);
        threadField.setDisable(running || !"Manual".equals(threadMode.getValue()));
        repeats.setDisable(running);
        warmup.setDisable(running);
        timeout.setDisable(running);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }
}