#ifndef STATS_H
#define STATS_H

#include "runner.h"
#include <math.h>
/* Fills the summary fields of r from r->samples[0..r->runs-1].
 * Only clean runs (started, not timed out, no signal, exit 0) count
 * toward wall/user/sys stats. max_rss_kb is the peak over all runs.
 * With no clean runs, the summary fields stay 0. */
void stats_compute(struct run_result *r);

#endif
