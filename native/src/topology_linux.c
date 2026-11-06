#include "include/topology.h"
#include "include/util.h"

void group_physical_cores(struct machine *m) {
    int n = m->logical_count;
    int next = 0;

    for (int i = 0; i < n; i++)
        m->cpus[i].physical_core = -1;

    for (int i = 0; i < n; i++) {
        if (m->cpus[i].physical_core != -1) continue;

        m->cpus[i].physical_core = next;
        for (int j = i + 1; j < n; j++) {
            if (m->cpus[j].physical_core == -1 &&
                m->cpus[j].package == m->cpus[i].package &&
                m->cpus[j].core_id == m->cpus[i].core_id) {
                m->cpus[j].physical_core = next;
                }
        }
        next++;
    }
    m->physical_count = next;
}

void classify_from_lists(struct machine *m, const struct cpu_range *perf, const struct cpu_range *eff) {
    for (int i = 0; i < m->logical_count; i++) {
        int id = m->cpus[i].id;
        if (cpulist_contains(perf, id))
            m->cpus[i].type = CORE_PERFORMANCE;
        else if (cpulist_contains(eff, id))
            m->cpus[i].type = CORE_EFFICIENCY;
        else
            m->cpus[i].type = CORE_UNIFORM;
    }
}

void classify_from_freq(struct machine *m) {
    int n = m->logical_count;
    if (n == 0) return;

    long lo = m->cpus[0].max_khz;
    long hi = m->cpus[0].max_khz;
    int unknown = 0;

    for (int i = 0; i < n; i++) {
        long f = m->cpus[i].max_khz;
        if (f < 0) unknown = 1;
        if (f < lo) lo = f;
        if (f > hi) hi = f;
    }

    int uniform = unknown || lo == hi;
    for (int i = 0; i < n; i++) {
        if (uniform)
            m->cpus[i].type = CORE_UNIFORM;
        else if (m->cpus[i].max_khz == hi)
            m->cpus[i].type = CORE_PERFORMANCE;
        else
            m->cpus[i].type = CORE_EFFICIENCY;
    }
}

void classify_core_types(struct machine *m) {
    char a[256], b[256];
    struct cpu_range *perf = NULL, *eff = NULL;

    int have = read_line("/sys/devices/cpu_core/cpus", a, sizeof a) == 0 &&
    read_line("/sys/devices/cpu_atom/cpus", b, sizeof b) == 0 &&
    parse_cpulist(a, &perf) == 0 &&
    parse_cpulist(b, &eff) == 0;

    if (have) classify_from_lists(m, perf, eff);
    else      classify_from_freq(m);

    cpulist_free(perf);
    cpulist_free(eff);
}

void machine_free(struct machine *m) {
    if (m == NULL) return;
    for (int i = 0; i < m->logical_count; i++) {
        cpulist_free(m->cpus[i].siblings);
        m->cpus[i].siblings = NULL;
    }
    for (int i = 0; i < m->cache_count; i++) {
        cpulist_free(m->caches[i].shared_cpus);
        m->caches[i].shared_cpus = NULL;
    }
}

static const char *type_name(enum core_type t) {
    switch (t) {
        case CORE_PERFORMANCE: return "performance";
        case CORE_EFFICIENCY:  return "efficiency";
        default:               return "uniform";
    }
}

static void print_json_string(FILE *out, const char *s) {
    fputc('"', out);
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        switch (c) {
            case '"':  fputs("\\\"", out); break;
            case '\\': fputs("\\\\", out); break;
            case '\n': fputs("\\n", out);  break;
            case '\r': fputs("\\r", out);  break;
            case '\t': fputs("\\t", out);  break;
            default:
                if (c < 0x20) fprintf(out, "\\u%04x", c);
                else fputc(c, out);
        }
    }
    fputc('"', out);
}

static void print_json_cpulist(FILE *out, const struct cpu_range *list) {
    int first = 1;
    fputc('[', out);
    for (; list != NULL; list = list->next) {
        for (int c = list->start; c <= list->end; c++) {
            if (!first) fputs(", ", out);
            fprintf(out, "%d", c);
            first = 0;
        }
    }
    fputc(']', out);
}

void machine_print_json(const struct machine *m, FILE *out) {
    if (m == NULL || out == NULL) return;

    fputs("{\n  \"model\": ", out);
    print_json_string(out, m->model);
    fprintf(out, ",\n  \"ram_bytes\": %llu,\n", (unsigned long long)m->ram_bytes);
    fprintf(out, "  \"logical_count\": %d,\n", m->logical_count);
    fprintf(out, "  \"physical_count\": %d,\n", m->physical_count);

    fputs("  \"cpus\": [", out);
    for (int i = 0; i < m->logical_count; i++) {
        const struct logical_cpu *c = &m->cpus[i];
        fputs(i ? ",\n    " : "\n    ", out);
        fprintf(out,
                "{\"id\": %d, \"package\": %d, \"core_id\": %d, \"physical_core\": %d, "
                "\"type\": \"%s\", \"max_khz\": %ld, \"siblings\": ",
                c->id, c->package, c->core_id, c->physical_core,
                type_name(c->type), c->max_khz);
        print_json_cpulist(out, c->siblings);
        fputc('}', out);
    }
    fputs(m->logical_count ? "\n  ],\n" : "],\n", out);

    fputs("  \"caches\": [", out);
    for (int i = 0; i < m->cache_count; i++) {
        const struct cache_info *k = &m->caches[i];
        fputs(i ? ",\n    " : "\n    ", out);
        fprintf(out, "{\"level\": %d, \"type\": ", k->level);
        print_json_string(out, k->type);
        fprintf(out, ", \"size_bytes\": %llu, \"shared_cpus\": ", (unsigned long long)k->size_bytes);
        print_json_cpulist(out, k->shared_cpus);
        fputc('}', out);
    }
    fputs(m->cache_count ? "\n  ]\n}\n" : "]\n}\n", out);
}


static struct cpu_range *single_range(int id) {
    struct cpu_range *r = malloc(sizeof *r);
    if (r != NULL) {
        r->start = id;
        r->end = id;
        r->next = NULL;
    }
    return r;
}

static void read_model(struct machine *m) {
    m->model[0] = '\0';
    FILE *f = fopen("/proc/cpuinfo", "r");
    if (f != NULL) {
        char line[512];
        while (fgets(line, sizeof line, f) != NULL) {
            if (strncmp(line, "model name", 10) != 0) continue;
            char *colon = strchr(line, ':');
            if (colon != NULL) {
                colon++;
                while (*colon == ' ' || *colon == '\t') colon++;
                colon[strcspn(colon, "\n")] = '\0';
                snprintf(m->model, sizeof m->model, "%s", colon);
            }
            break;
        }
        fclose(f);
    }
    if (m->model[0] == '\0') snprintf(m->model, sizeof m->model, "unknown");
}

static void read_ram(struct machine *m) {
    m->ram_bytes = 0;
    FILE *f = fopen("/proc/meminfo", "r");
    if (f == NULL) return;
    char line[256];
    while (fgets(line, sizeof line, f) != NULL) {
        unsigned long long kb;
        if (sscanf(line, "MemTotal: %llu kB", &kb) == 1) {
            m->ram_bytes = kb * 1024ULL;
            break;
        }
    }
    fclose(f);
}

static int read_cpus(struct machine *m) {
    char buf[256];
    struct cpu_range *online = NULL;
    if (read_line(SYS_CPU "/online", buf, sizeof buf) != 0 ||
        parse_cpulist(buf, &online) != 0)
        return -1;

    int n = 0;
    for (struct cpu_range *r = online; r != NULL; r = r->next) {
        for (int id = r->start; id <= r->end; id++) {
            if (n >= MAX_LOGICAL) {
                cpulist_free(online);
                return -1;
            }
            struct logical_cpu *c = &m->cpus[n];
            char path[256];
            long v;

            c->id = id;

            snprintf(path, sizeof path, SYS_CPU "/cpu%d/topology/physical_package_id", id);
            c->package = read_long(path, &v) == 0 ? (int)v : 0;

            snprintf(path, sizeof path, SYS_CPU "/cpu%d/topology/core_id", id);
            c->core_id = read_long(path, &v) == 0 ? (int)v : id;

            snprintf(path, sizeof path, SYS_CPU "/cpu%d/cpufreq/cpuinfo_max_freq", id);
            c->max_khz = read_long(path, &v) == 0 ? v : -1;

            snprintf(path, sizeof path, SYS_CPU "/cpu%d/topology/thread_siblings_list", id);
            c->siblings = NULL;
            if (read_line(path, buf, sizeof buf) != 0 || parse_cpulist(buf, &c->siblings) != 0)
                c->siblings = single_range(id);

            n++;
            m->logical_count = n;
        }
    }
    cpulist_free(online);
    return 0;
}

static int cache_file(int cpu, int idx, const char *name, char *buf, size_t size) {
    char path[256];
    snprintf(path, sizeof path, SYS_CPU "/cpu%d/cache/index%d/%s", cpu, idx, name);
    return read_line(path, buf, size);
}

static int read_caches(struct machine *m) {
    for (int i = 0; i < m->logical_count; i++) {
        int cpu = m->cpus[i].id;
        for (int idx = 0; ; idx++) {
            char buf[256];
            char *end;

            if (cache_file(cpu, idx, "level", buf, sizeof buf) != 0) break;
            long level = strtol(buf, &end, 10);
            if (end == buf) continue;

            struct cpu_range *shared = NULL;
            if (cache_file(cpu, idx, "shared_cpu_list", buf, sizeof buf) != 0 ||
                parse_cpulist(buf, &shared) != 0 || shared == NULL) {
                cpulist_free(shared);
            continue;
                }
                if (shared->start != cpu) {
                    cpulist_free(shared);
                    continue;
                }
                if (m->cache_count >= MAX_CACHES) {
                    cpulist_free(shared);
                    return -1;
                }

                struct cache_info *k = &m->caches[m->cache_count];
                k->level = (int)level;
                k->shared_cpus = shared;
                k->size_bytes = 0;
                snprintf(k->type, sizeof k->type, "unknown");

                if (cache_file(cpu, idx, "type", buf, sizeof buf) == 0)
                    snprintf(k->type, sizeof k->type, "%s", buf);
            if (cache_file(cpu, idx, "size", buf, sizeof buf) == 0)
                parse_size(buf, &k->size_bytes);

            m->cache_count++;
        }
    }
    return 0;
}

int detect_topology(struct machine *m) {
    if (m == NULL) return -1;
    memset(m, 0, sizeof *m);

    read_model(m);
    read_ram(m);

    if (read_cpus(m) != 0 || read_caches(m) != 0) {
        machine_free(m);
        return -1;
    }

    group_physical_cores(m);
    classify_core_types(m);
    return 0;
}
