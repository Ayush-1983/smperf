// runner.h
#ifndef RUNNER_H
#define RUNNER_H

#include "util.h"
#include <sys/types.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <time.h>
#define MAX_ARGS         32
#define MAX_REPEATS      64
#define EXEC_FAILED_EXIT 127   /* child's exit code when exec itself fails */
#define AFFINITY_FAILED_EXIT 126  /* child's exit code when sched_setaffinity fails */
#define ARG_LEN 256
/* What to run and how. */

enum run_status {
    RUN_OK         =  0,
    RUN_BAD_PARAMS = -1,   /* repeats outside 1..MAX_REPEATS */
    RUN_RUNNER_ERR = -2,   /* fork or wait4 failed */
    RUN_BAD_PATH   = -3    /* exec failed, program never started */
};

/* j->warmup discarded launches, then j->repeats measured ones via run_once.
 * Returns a run_status. *r is zeroed before any early return. */
struct job {
    char path[512];
    char *args[MAX_ARGS];      /* args[0] = program name, list ends with NULL */
    int threads;               /* exported to the child as OMP_NUM_THREADS */
    struct cpu_range *cpus;    /* NULL = do not pin */
    int warmup;                /* unmeasured runs before timing starts */
    int repeats;               /* measured runs, 1..MAX_REPEATS */
    double timeout_sec;        /* per run; <= 0 means no timeout */
    char arg_store[MAX_ARGS][ARG_LEN];
};

/* Outcome of ONE launch. Filled by run_once. */
struct run_sample {
    int started;               /* 1 if the program really started (exec worked) */
    int exit_code;             /* valid only when term_signal == 0 */
    int term_signal;           /* signal that killed it, 0 if it exited normally */
    int timed_out;             /* 1 if we killed it for exceeding timeout_sec */
    double wall_sec;
    double user_sec;
    double sys_sec;
    long max_rss_kb;
};

/* Summary over all measured repeats (warmups excluded). Filled by run_job. */
struct run_result {
    int runs;                  /* measured runs completed */
    int failed_runs;           /* measured runs that did not finish cleanly */
    double wall_best;
    double wall_mean;
    double wall_max;
    double wall_stddev;
    double user_mean;
    double sys_mean;
    long max_rss_kb;           /* peak over all runs */
    struct run_sample samples[MAX_REPEATS];
};


int run_once(const struct job *j, struct run_sample *s);

int job_expand_args(struct job *j, const char *const *tmpl, int tmpl_count);


int run_job(const struct job *j, struct run_result *r);
#endif
