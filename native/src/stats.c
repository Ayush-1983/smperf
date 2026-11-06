#include "include/stats.h"


static int is_clean(const struct run_sample *s) {
    return s->started && !s->timed_out &&
    s->term_signal == 0 && s->exit_code == 0;
}

void stats_compute(struct run_result *r) {
    int n = 0;
    double wall_sum = 0, user_sum = 0, sys_sum = 0;

    r->wall_best = r->wall_mean = r->wall_max = r->wall_stddev = 0;
    r->user_mean = r->sys_mean = 0;
    r->max_rss_kb = 0;

    for (int i = 0; i < r->runs; i++) {
        const struct run_sample *s = &r->samples[i];

        if (s->max_rss_kb > r->max_rss_kb) r->max_rss_kb = s->max_rss_kb;
        if (!is_clean(s)) continue;

        if (n == 0 || s->wall_sec < r->wall_best) r->wall_best = s->wall_sec;
        if (n == 0 || s->wall_sec > r->wall_max)  r->wall_max  = s->wall_sec;
        wall_sum += s->wall_sec;
        user_sum += s->user_sec;
        sys_sum  += s->sys_sec;
        n++;
    }
    if (n == 0) return;

    r->wall_mean = wall_sum / n;
    r->user_mean = user_sum / n;
    r->sys_mean  = sys_sum / n;

    if (n >= 2) {
        double sq = 0;
        for (int i = 0; i < r->runs; i++) {
            if (!is_clean(&r->samples[i])) continue;
            double d = r->samples[i].wall_sec - r->wall_mean;
            sq += d * d;
        }
        r->wall_stddev = sqrt(sq / (n - 1));
    }
}
