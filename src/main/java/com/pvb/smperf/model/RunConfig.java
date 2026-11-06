package com.pvb.smperf.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** What the user asked for. An empty threads list means "auto". */
public record RunConfig(
        Path binary,
        List<String> argsTemplate,
        List<Integer> threads,
        int repeats,
        int warmup,
        double timeoutSec) {

    // The limits mirror cli.c, so bad input is caught before launching.
    public RunConfig {
        Objects.requireNonNull(binary, "binary");
        argsTemplate = List.copyOf(argsTemplate);
        threads = List.copyOf(threads);
        if (repeats < 1 || repeats > 64)       throw new IllegalArgumentException("repeats must be 1..64");
        if (warmup < 0 || warmup > 100)        throw new IllegalArgumentException("warmup must be 0..100");
        if (timeoutSec < 0 || timeoutSec > 86400) throw new IllegalArgumentException("timeout must be 0..86400");
    }

    /** The full command line for the engine. No shell is involved, so no quoting issues. */
    public List<String> toCommand(Path engine) {
        List<String> cmd = new ArrayList<>();
        cmd.add(engine.toString());
        cmd.add("run");
        cmd.add("--threads");
        cmd.add(threads.isEmpty()
                ? "auto"
                : threads.stream().map(String::valueOf).collect(Collectors.joining(",")));
        cmd.add("--repeats");
        cmd.add(String.valueOf(repeats));
        cmd.add("--warmup");
        cmd.add(String.valueOf(warmup));
        cmd.add("--timeout");
        cmd.add(String.valueOf(timeoutSec));
        cmd.add("--");
        cmd.add(binary.toAbsolutePath().toString());
        cmd.addAll(argsTemplate);
        return cmd;
    }
}