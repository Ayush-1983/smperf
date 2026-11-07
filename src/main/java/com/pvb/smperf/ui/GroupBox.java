package com.pvb.smperf.ui;

import javafx.beans.DefaultProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Classic "group box": an etched frame with its title cut into the top border.
 * Works from FXML too: {@code <GroupBox title="Run settings"> ...children... </GroupBox>}.
 */
@DefaultProperty("content")
public class GroupBox extends StackPane {

    private final Label titleLbl = new Label();
    private final VBox box = new VBox(8);

    public GroupBox() {
        getStyleClass().add("group-wrap");
        box.getStyleClass().add("group-box");
        box.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        StackPane.setMargin(box, new Insets(8, 0, 0, 0));

        titleLbl.getStyleClass().add("group-title");
        StackPane.setAlignment(titleLbl, Pos.TOP_LEFT);
        StackPane.setMargin(titleLbl, new Insets(0, 0, 0, 10));

        getChildren().addAll(box, titleLbl);
    }

    public GroupBox(String title, Node... children) {
        this();
        setTitle(title);
        box.getChildren().addAll(children);
    }

    /** Children go inside the frame (this is the FXML default property). */
    public ObservableList<Node> getContent() { return box.getChildren(); }

    public String getTitle() { return titleLbl.getText(); }
    public void setTitle(String title) { titleLbl.setText(title); }

    public double getSpacing() { return box.getSpacing(); }
    public void setSpacing(double s) { box.setSpacing(s); }
}
