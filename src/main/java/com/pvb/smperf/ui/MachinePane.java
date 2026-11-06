package com.pvb.smperf.ui;

import com.pvb.smperf.model.MachineInfo;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Shows the machine summary and detailed hardware layout. */
public class MachinePane extends VBox {

    private final Label model = new Label("Detecting machine...");
    private final Label summary = new Label();
    private final Label cacheLine = new Label();
    private final FlowPane coreGrid = new FlowPane(8, 8);

    // Additional containers for detailed specs that you can populate later
    private final VBox detailedSpecsBox = new VBox(6);

    private final Label legend = new Label(
            "Chips in one box are hyperthreads of the same physical core. "
                    + "Blue = performance, green = efficiency.");

    public MachinePane() {
        getStyleClass().add("machine-pane");
        setSpacing(14);
        setPadding(new Insets(16));

        model.getStyleClass().add("machine-model");
        summary.getStyleClass().add("muted");
        cacheLine.getStyleClass().add("muted");
        legend.getStyleClass().add("muted");
        legend.setWrapText(true);

        // Core topology wrapper card
        VBox topologyCard = new VBox(10);
        topologyCard.getStyleClass().add("section");
        Label topoHeading = new Label("Logical & Physical Core Mapping");
        topoHeading.getStyleClass().add("field-label");

        coreGrid.setPadding(new Insets(4, 0, 4, 0));
        topologyCard.getChildren().addAll(topoHeading, coreGrid, legend);

        // Detailed Specs placeholder card (ready for your code)
        VBox specsCard = new VBox(8);
        specsCard.getStyleClass().add("section");
        Label specsHeading = new Label("Detailed Architecture Metrics");
        specsHeading.getStyleClass().add("field-label");

        detailedSpecsBox.getChildren().add(new Label("Waiting for extended telemetry..."));
        specsCard.getChildren().addAll(specsHeading, new Separator(), detailedSpecsBox);

        getChildren().addAll(
                model,
                summary,
                cacheLine,
                new Separator(),
                topologyCard,
                specsCard
        );
    }

    public void show(MachineInfo m) {
        model.getStyleClass().remove("error-text");
        model.setText(m.model());

        Map<Integer, List<MachineInfo.Cpu>> byCore = m.cpus().stream()
                .collect(Collectors.groupingBy(MachineInfo.Cpu::physicalCore,
                        LinkedHashMap::new, Collectors.toList()));

        long p = byCore.values().stream().filter(c -> c.get(0).type().equals("performance")).count();
        long e = byCore.values().stream().filter(c -> c.get(0).type().equals("efficiency")).count();
        String mix = (p > 0 && e > 0) ? String.format(" (%d P + %d E)", p, e) : "";
        double gib = m.ramBytes() / (1024.0 * 1024 * 1024);

        summary.setText(String.format(Locale.ROOT, "%d cores%s · %d threads · %.1f GiB RAM",
                m.physicalCount(), mix, m.logicalCount(), gib));

        m.caches().stream().filter(c -> c.level() == 3).findFirst().ifPresentOrElse(
                c -> cacheLine.setText(String.format(Locale.ROOT, "L3 cache: %d MB shared",
                        c.sizeBytes() / (1024 * 1024))),
                () -> cacheLine.setText(""));

        coreGrid.getChildren().clear();
        for (List<MachineInfo.Cpu> core : byCore.values()) {
            HBox box = new HBox(4);
            box.getStyleClass().add("core-box");
            for (MachineInfo.Cpu cpu : core) {
                box.getChildren().add(chip(cpu));
            }
            coreGrid.getChildren().add(box);
        }
    }

    public void showError(String message) {
        model.setText("Engine unavailable");
        if (!model.getStyleClass().contains("error-text")) {
            model.getStyleClass().add("error-text");
        }
        summary.setText(message);
        cacheLine.setText("");
        coreGrid.getChildren().clear();
    }

    private static Label chip(MachineInfo.Cpu cpu) {
        Label chip = new Label(String.valueOf(cpu.id()));
        chip.getStyleClass().addAll("cpu-chip", "type-" + cpu.type());
        String freq = cpu.maxKhz() > 0
                ? String.format(Locale.ROOT, " · max %.1f GHz", cpu.maxKhz() / 1_000_000.0)
                : "";
        chip.setTooltip(new Tooltip("CPU " + cpu.id() + " · " + cpu.type() + freq));
        return chip;
    }

    /** Hook to let you inject custom detailed specs from your backend engine later. */
    public void setDetailedSpecsContent(List<String> metricLines) {
        detailedSpecsBox.getChildren().clear();
        if (metricLines.isEmpty()) {
            detailedSpecsBox.getChildren().add(new Label("No additional metrics available."));
            return;
        }
        for (String line : metricLines) {
            Label lbl = new Label(line);
            lbl.getStyleClass().add("muted");
            detailedSpecsBox.getChildren().add(lbl);
        }
    }
}