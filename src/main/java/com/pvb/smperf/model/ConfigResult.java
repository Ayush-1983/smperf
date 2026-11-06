package com.pvb.smperf.model;

import com.pvb.smperf.engine.EngineEvent;

/** One table row: the raw stats from C plus the numbers derived in Java. */
public record ConfigResult(EngineEvent.Result stats, double speedup, double efficiency) {

    /** baseline is the first good result of the run; speedup uses wall_best. */
    public static ConfigResult of(EngineEvent.Result r, EngineEvent.Result baseline) {
        if (r.allFailed() || baseline == null || baseline.allFailed()) {
            return new ConfigResult(r, Double.NaN, Double.NaN);
        }
        double speedup = baseline.wallBest() / r.wallBest();
        double threadRatio = (double) r.threads() / baseline.threads();
        return new ConfigResult(r, speedup, speedup / threadRatio);
    }
}