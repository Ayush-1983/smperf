module com.pvb.smperf {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires com.google.gson;
    opens com.pvb.smperf.model to com.google.gson;
    opens com.pvb.smperf.engine to com.google.gson;
    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;

    opens com.pvb.smperf to javafx.fxml;
    opens com.pvb.smperf.ui to javafx.fxml;
    exports com.pvb.smperf;
}