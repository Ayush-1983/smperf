
package com.pvb.smperf.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvb.smperf.model.MachineInfo;
import com.pvb.smperf.model.RunConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

public class EngineClient {

    private final Path enginePath;
    private volatile Process activeProcess;

    public EngineClient(Path enginePath) {
        this.enginePath = enginePath.toAbsolutePath();
    }

    public MachineInfo fetchMachine() throws Exception {

        Process process = new ProcessBuilder(
                enginePath.toString(),
                "topology"
        ).start();

        String output;

        try (var reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            output = reader.lines()
                    .reduce("", (a, b) -> a + b);
        }

        String error = new String(
                process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8
        );

        int exit = process.waitFor();

        if (exit != 0) {
            throw new IllegalStateException(
                    "Topology detection failed: " + error
            );
        }

        return MachineInfo.fromJson(output);
    }

    public void run(
            RunConfig config,
            Consumer<EngineEvent> listener
    ) throws Exception {

        Process process = new ProcessBuilder(
                config.toCommand(enginePath)
        ).start();

        activeProcess = process;

        Thread stderrReader = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(
                            process.getErrorStream(),
                            StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    System.err.println("[mperf] " + line);
                }

            } catch (Exception ignored) {
            }
        });

        try (var reader = new BufferedReader(
                new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                EngineEvent event = parseEvent(line);

                if (event != null) {
                    listener.accept(event);
                }
            }
        } finally {
            int exit = process.waitFor();
            stderrReader.join();
            activeProcess = null;

            if (exit != 0) {
                throw new IllegalStateException(
                        "mperf exited with status " + exit
                );
            }
        }
    }

    public void cancel() {

        Process process = activeProcess;

        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    private EngineEvent parseEvent(String line) {

        JsonObject json = JsonParser.parseString(line)
                .getAsJsonObject();

        String event = json.get("event").getAsString();

        return switch (event) {

            case "result" -> new EngineEvent.Result(
                    json.get("threads").getAsInt(),
                    json.get("runs").getAsInt(),
                    json.get("failed").getAsInt(),
                    json.get("wall_best").getAsDouble(),
                    json.get("wall_mean").getAsDouble(),
                    json.get("wall_max").getAsDouble(),
                    json.get("wall_stddev").getAsDouble(),
                    json.get("user_mean").getAsDouble(),
                    json.get("sys_mean").getAsDouble(),
                    json.get("max_rss_kb").getAsLong()
            );

            case "error" -> new EngineEvent.Failure(
                    json.get("threads").getAsInt(),
                    json.get("code").getAsInt(),
                    json.get("message").getAsString()
            );

            case "done" -> new EngineEvent.Done(
                    json.get("ok").getAsBoolean(),
                    json.get("cancelled").getAsBoolean()
            );

            default -> null;
        };
    }
}
