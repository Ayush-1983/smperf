package com.pvb.smperf.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/** The CPU topology view in its own (non-modal) window. Built once, re-shown on demand. */
public final class TopologyWindow {

    private final Parent content;
    private final Workspace ws;
    private Stage stage;

    public TopologyWindow(Parent content, Workspace ws) {
        this.content = content;
        this.ws = ws;
    }

    public void show() {
        if (stage == null) build();
        if (stage.isIconified()) stage.setIconified(false);
        stage.show();
        stage.toFront();
        stage.requestFocus();
    }

    private void build() {
        BorderPane shell = new BorderPane(content);
        shell.getStyleClass().add("win-window");

        Scene scene = new Scene(shell, 940, 660);
        Window owner = ws.window();
        if (owner != null && owner.getScene() != null)
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        scene.getAccelerators().put(KeyCombination.keyCombination("Esc"), () -> stage.close());

        stage = new Stage();
        stage.setTitle("SMPerf — CPU Topology");
        stage.setScene(scene);
        stage.setMinWidth(640);
        stage.setMinHeight(420);
        if (owner != null) {
            stage.initOwner(owner);
            stage.setX(owner.getX() + 60);
            stage.setY(owner.getY() + 50);
        }
    }
}