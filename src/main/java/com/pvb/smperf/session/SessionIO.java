package com.pvb.smperf.session;

import com.google.gson.*;
import com.pvb.smperf.engine.EngineEvent;
import com.pvb.smperf.model.ConfigResult;
import com.pvb.smperf.model.MachineInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Reads and writes session files. No JavaFX in here. */
public final class SessionIO {

    public static final String FORMAT = "smperf-session";
    public static final int VERSION = 1;

    public record Loaded(MachineInfo machine, SavedConfig config,
                         List<EngineEvent.Result> results, String savedAt) {}

    // snake_case on the wire, same as the engine's JSON
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .create();

    // CSV columns = the engine's result field names
    private static final List<String> RAW = List.of(
            "threads", "runs", "failed", "wall_best", "wall_mean", "wall_max",
            "wall_stddev", "user_mean", "sys_mean", "max_rss_kb");
    private static final Set<String> INT_COLS = Set.of("threads", "runs", "failed", "max_rss_kb");

    private SessionIO() {}

    // ---------------------------------------------------------------- JSON

    public static void saveJson(Path p, MachineInfo machine, SavedConfig config,
                                List<ConfigResult> rows) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("version", VERSION);
        o.addProperty("saved_at", Instant.now().toString());
        o.addProperty("app", "SMPerf");
        if (machine != null) o.add("machine", GSON.toJsonTree(machine));
        if (config != null) o.add("config", GSON.toJsonTree(config));
        JsonArray arr = new JsonArray();
        for (ConfigResult r : rows) arr.add(GSON.toJsonTree(r.stats()));
        o.add("results", arr);
        writeAtomic(p, GSON.toJson(o));
    }

    private static Loaded parseJson(String text) throws IOException {
        try {
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            if (!o.has("format") || !FORMAT.equals(o.get("format").getAsString()))
                throw new IOException("not an SMPerf session file");
            if (o.has("version") && o.get("version").getAsInt() > VERSION)
                throw new IOException("file was written by a newer SMPerf version");

            JsonArray arr = o.getAsJsonArray("results");
            if (arr == null || arr.isEmpty()) throw new IOException("file contains no results");
            List<EngineEvent.Result> results = new ArrayList<>();
            for (JsonElement el : arr) results.add(GSON.fromJson(el, EngineEvent.Result.class));

            MachineInfo machine = null;
            if (o.has("machine")) {
                try { machine = MachineInfo.fromJson(o.get("machine").toString()); }
                catch (Exception ignored) { /* machine block is optional */ }
            }
            SavedConfig config = o.has("config") ? GSON.fromJson(o.get("config"), SavedConfig.class) : null;
            String savedAt = o.has("saved_at") ? o.get("saved_at").getAsString() : null;
            return new Loaded(machine, config, results, savedAt);
        } catch (RuntimeException e) {          // JsonParseException, ClassCast, NumberFormat...
            throw new IOException("malformed session file (" + e.getMessage() + ")");
        }
    }

    // ----------------------------------------------------------------- CSV

    public static void exportCsv(Path p, List<ConfigResult> rows) throws IOException {
        StringBuilder sb = new StringBuilder(String.join(",", RAW)).append(",speedup,efficiency\n");
        for (ConfigResult r : rows) {
            var s = r.stats();
            sb.append(s.threads()).append(',').append(s.runs()).append(',').append(s.failed()).append(',')
                    .append(f(s.wallBest())).append(',').append(f(s.wallMean())).append(',')
                    .append(f(s.wallMax())).append(',').append(f(s.wallStddev())).append(',')
                    .append(f(s.userMean())).append(',').append(f(s.sysMean())).append(',')
                    .append(s.maxRssKb()).append(',')
                    .append(Double.isNaN(r.speedup()) ? "" : f(r.speedup())).append(',')
                    .append(Double.isNaN(r.efficiency()) ? "" : f(r.efficiency())).append('\n');
        }
        writeAtomic(p, sb.toString());
    }

    private static List<EngineEvent.Result> parseCsv(String text) throws IOException {
        List<EngineEvent.Result> out = new ArrayList<>();
        Map<String, Integer> col = null;
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            if (lineNo == 1 && raw.startsWith("\uFEFF")) raw = raw.substring(1);   // Excel BOM
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cells = line.split(",", -1);

            if (col == null) {                                   // header row
                col = new HashMap<>();
                for (int i = 0; i < cells.length; i++) col.put(cells[i].strip().toLowerCase(Locale.ROOT), i);
                for (String need : RAW)
                    if (!col.containsKey(need)) throw new IOException("CSV is missing column \"" + need + "\"");
                continue;
            }

            JsonObject row = new JsonObject();
            for (String key : RAW) {
                int i = col.get(key);
                if (i >= cells.length) throw new IOException("line " + lineNo + ": too few columns");
                String v = cells[i].strip();
                try {
                    if (INT_COLS.contains(key)) row.addProperty(key, Long.parseLong(v));
                    else row.addProperty(key, Double.parseDouble(v));
                } catch (NumberFormatException e) {
                    throw new IOException("line " + lineNo + ": \"" + v + "\" is not a number (" + key + ")");
                }
            }
            out.add(GSON.fromJson(row, EngineEvent.Result.class));
        }
        if (out.isEmpty()) throw new IOException("no result rows found");
        return out;
    }

    // ---------------------------------------------------------------- shared

    public static Loaded load(Path p) throws IOException {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (name.endsWith(".csv"))  return new Loaded(null, null, parseCsv(text), null);
        if (name.endsWith(".json")) return parseJson(text);
        throw new IOException("unsupported file type (use .json or .csv)");
    }

    /** Same baseline rule as the live run: first configuration that did not fully fail. */
    public static List<ConfigResult> toConfigResults(List<EngineEvent.Result> raw) {
        List<ConfigResult> out = new ArrayList<>();
        EngineEvent.Result baseline = null;
        for (EngineEvent.Result r : raw) {
            if (baseline == null && !r.allFailed()) baseline = r;
            out.add(ConfigResult.of(r, baseline));
        }
        return out;
    }

    private static String f(double v) { return String.format(Locale.ROOT, "%.6f", v); }

    private static void writeAtomic(Path p, String content) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }
}