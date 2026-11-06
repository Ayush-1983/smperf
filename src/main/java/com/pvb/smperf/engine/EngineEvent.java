package com.pvb.smperf.engine;

/** One parsed JSON line from "mperf run". */
public sealed interface EngineEvent {

    record Result(
            int threads, int runs, int failed,
            double wallBest, double wallMean, double wallMax, double wallStddev,
            double userMean, double sysMean, long maxRssKb) implements EngineEvent {

        public boolean allFailed() { return failed == runs; }
    }

    /** The "error" event. Not named Error, to avoid clashing with java.lang.Error. */
    record Failure(int threads, int code, String message) implements EngineEvent {}

    record Done(boolean ok, boolean cancelled) implements EngineEvent {}
}