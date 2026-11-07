package com.pvb.smperf.ui;

import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.*;
import java.util.function.Supplier;

/**
 * Nav strip + view switching inside the SAME scene (no extra stages), plus a message bar
 * that replaces pop-up alerts. New features = register(new View(...)).
 */
public final class Workspace {

    public record View(String id, String title, Supplier<Node> content, Runnable onShow) {
        public View(String id, String title, Supplier<Node> content) { this(id, title, content, () -> {}); }
    }

    private final BorderPane root;
    private final Map<String, View> views = new LinkedHashMap<>();
    private final Map<String, Node> built = new HashMap<>();
    private final Map<String, ToggleButton> tabs = new LinkedHashMap<>();
    private final ToggleGroup group = new ToggleGroup();
    private final HBox strip = new HBox(2);
    private final HBox msgBar = new HBox(8);
    private final Label msgText = new Label();
    private final PauseTransition autoHide = new PauseTransition(Duration.seconds(6));
    private final ReadOnlyStringWrapper current = new ReadOnlyStringWrapper();

    public Workspace(BorderPane root) {
        this.root = root;
        strip.getStyleClass().add("nav-strip");
        msgBar.getStyleClass().add("msg-bar");
        msgBar.setAlignment(Pos.CENTER_LEFT);
        hideBar();

        VBox top = new VBox();
        if (root.getTop() != null) top.getChildren().add(root.getTop());   // keep the existing MenuBar
        top.getChildren().addAll(strip, msgBar);
        root.setTop(top);

        group.selectedToggleProperty().addListener((o, old, now) -> {
            if (now == null) { if (old != null) old.setSelected(true); return; }   // never deselect
            activate((String) now.getUserData());
        });
    }

    // ------------------------------------------------------------ views

    public void register(View v) {
        views.put(v.id(), v);
        ToggleButton t = new ToggleButton(v.title());
        t.getStyleClass().add("nav-tab");
        t.setUserData(v.id());
        t.setToggleGroup(group);
        t.setFocusTraversable(false);
        tabs.put(v.id(), t);
        strip.getChildren().add(t);
    }

    public void show(String id) {
        ToggleButton t = tabs.get(id);
        if (t == null) throw new IllegalArgumentException("unknown view: " + id);
        if (t.isSelected()) activate(id); else t.setSelected(true);
    }

    private void activate(String id) {
        View v = views.get(id);
        root.setCenter(built.computeIfAbsent(id, k -> v.content().get()));
        current.set(id);
        v.onShow().run();            // lets a view refresh itself every time it is opened
    }

    public ReadOnlyStringProperty currentProperty() { return current.getReadOnlyProperty(); }
    public String currentId() { return current.get(); }

    public void setTabTitle(String id, String text) {
        ToggleButton t = tabs.get(id);
        if (t != null) t.setText(text);
    }

    /** Puts "Home / Results / Session  (Ctrl+1..n)" at the top of the View menu. */
    public void installViewMenu(MenuBar menuBar) {
        Menu view = menuBar.getMenus().stream().filter(m -> "View".equals(m.getText()))
                .findFirst().orElse(null);
        if (view == null) return;
        int i = 0;
        for (View v : views.values()) {
            MenuItem mi = new MenuItem(v.title());
            mi.setAccelerator(KeyCombination.keyCombination("Shortcut+" + (i + 1)));
            mi.setOnAction(e -> show(v.id()));
            view.getItems().add(i++, mi);
        }
        view.getItems().add(i, new SeparatorMenuItem());
    }

    public Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ------------------------------------------------------ message bar

    public void info(String text)  { showBar(text, false, false); }
    public void error(String text) { showBar(text, true, false); }

    /** Inline confirmation instead of a modal dialog. */
    public void ask(String text, String yesLabel, Runnable onYes) {
        Button yes = new Button(yesLabel);
        yes.getStyleClass().add("secondary-button");
        yes.setOnAction(e -> { hideBar(); onYes.run(); });
        Button no = new Button("Cancel");
        no.setOnAction(e -> hideBar());
        showBar(text, false, true, yes, no);
    }

    private void showBar(String text, boolean isError, boolean sticky, Button... actions) {
        autoHide.stop();
        msgText.setText(text);
        msgBar.getStyleClass().remove("msg-error");
        if (isError) msgBar.getStyleClass().add("msg-error");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("×");
        close.getStyleClass().add("msg-close");
        close.setOnAction(e -> hideBar());

        msgBar.getChildren().setAll(msgText, spacer);
        msgBar.getChildren().addAll(actions);
        msgBar.getChildren().add(close);
        msgBar.setVisible(true);
        msgBar.setManaged(true);

        if (!sticky) {
            autoHide.setOnFinished(e -> hideBar());
            autoHide.playFromStart();
        }
    }

    private void hideBar() {
        autoHide.stop();
        msgBar.setVisible(false);
        msgBar.setManaged(false);
    }
}