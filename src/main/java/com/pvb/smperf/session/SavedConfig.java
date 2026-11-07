package com.pvb.smperf.session;

import java.util.List;
import java.util.stream.Collectors;

/** What was benchmarked. Stored in the JSON file for reproducibility. */
public record SavedConfig(String executable, List<String> args, List<Integer> threads,
                          int repeats, int warmup, double timeoutSec) {

    /** Empty thread list means "auto". */
    public String threadsLabel() {
        return threads == null || threads.isEmpty() ? "auto"
                : threads.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}