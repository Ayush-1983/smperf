package com.pvb.smperf;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("ui/main.fxml"));
        Scene scene = new Scene(loader.load(), 1100, 700);
        scene.getStylesheets().clear();

        scene.getStylesheets().add(getClass().getResource("/com/pvb/smperf/ui/style.css").toExternalForm());
        stage.setTitle("SMPerf");
        stage.setScene(scene);
        stage.show();
    }
}