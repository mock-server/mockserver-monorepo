#!/usr/bin/env python3
"""Regenerate history-<from>-<to>.json for perf-noise-budget-test.sh.

Input: a directory with one b<N>/ per build holding perf-result.json, perf-microbench-extra.json
and x/diag-samples.csv (extracted from perf-jvm-diagnostics.tgz), fetched per build with
  bk artifacts list <N> -p mockserver-performance-test
  bk artifacts download <ID> --build <N> -p mockserver-performance-test
Usage: generate-history.py <dir> > history.json

Kept per valid perf-queue run, in timestamp order:
  cpu.first_sample_ratio  the growth.cpu_pct.ratio the run recorded (end sample / first sample)
  growth_trace            [seconds, cpu_pct, event-log occupancy] for each diag-samples.csv row
                          from 8 s before to 8 s after the growth load (the longest stretch at
                          600-1000 req/s), so the test can run perf-test-run.sh's own
                          growth_cpu_window over it. Occupancy is max(entries / their bound,
                          bytes / their bound), as that function computes it.
  alloc.<benchmark>       microbench_extra alloc_bytes_per_op for the benchmarks listed in ALLOC
  pc.<arm>                path_coverage sample_count and p99_ms for the arms listed in ARMS
"""
import csv
import json
import os
import sys

ALLOC = ["MetricsIncrementBenchmark.incrementDistinctCounterPerThread",
         "MetricsIncrementBenchmark.incrementSharedCounter",
         "LocalCallbackDispatchBenchmark.inline",
         "SocksHandshakeBenchmark.detect_scenario-SOCKS5_PASSWORD",
         "ForwardPathBenchmark.renderSimple"]
ARMS = ["jsonpath_100", "jsonschema_100", "xpath_100", "download_32mib", "tls_keepalive", "mtls_churn", "h2_2000"]


def growth_trace(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            try:
                rows.append((int(r["ts"]), r))
            except (TypeError, ValueError):
                pass
    best, cur = None, None
    for (t0, a), (t1, b) in zip(rows, rows[1:]):
        try:
            rate = (float(b["req_dur_count"]) - float(a["req_dur_count"])) / (t1 - t0) if t1 > t0 else 0
        except (TypeError, ValueError):
            rate = 0
        if 600 <= rate <= 1000:
            cur = [cur[0], t1] if cur else [t0, t1]
            if best is None or cur[1] - cur[0] > best[1] - best[0]:
                best = list(cur)
        else:
            cur = None
    if not best or best[1] - best[0] < 300:
        return None

    def num(r, k):
        try:
            return float(r[k])
        except (TypeError, ValueError):
            return 0.0
    out = []
    for t, r in rows:
        if best[0] - 8 <= t <= best[1] + 8 and r.get("cpu_pct") not in ("", None):
            cu = num(r, "retained_entries") / num(r, "max_retained_entries") if num(r, "max_retained_entries") > 0 else 0
            bu = num(r, "retained_bytes") / num(r, "max_retained_bytes") if num(r, "max_retained_bytes") > 0 else 0
            out.append([t - best[0] + 8, float(r["cpu_pct"]), round(max(cu, bu), 4)])
    return out


def main(root):
    runs = []
    for b in os.listdir(root):
        d = os.path.join(root, b)
        try:
            r = json.load(open(os.path.join(d, "perf-result.json")))
        except (OSError, ValueError):
            continue
        if (r.get("validity") or {}).get("valid") is not True or r.get("run_arm") \
                or (r.get("agent") or {}).get("queue", "perf") != "perf":
            continue
        try:
            extra = json.load(open(os.path.join(d, "perf-microbench-extra.json"))).get("microbench_extra") or {}
        except (OSError, ValueError):
            extra = {}
        diag = os.path.join(d, "x", "diag-samples.csv")
        arms = (r.get("path_coverage") or {}).get("arms") or {}
        runs.append({
            "build": int(b.lstrip("b")), "ts": r["timestamp_utc"],
            "cpu": {"first_sample_ratio": ((r.get("growth") or {}).get("cpu_pct") or {}).get("ratio")},
            "growth_trace": growth_trace(diag) if os.path.exists(diag) else None,
            "alloc": {k: extra[k]["alloc_bytes_per_op"] for k in ALLOC if k in extra},
            "pc": {a: {"n": arms[a].get("sample_count", arms[a].get("measured_sample_count")), "p99_ms": arms[a].get("p99_ms")}
                   for a in ARMS if isinstance(arms.get(a), dict)},
        })
    runs.sort(key=lambda x: x["ts"])
    json.dump({"source": "mockserver-performance-test builds, valid perf-queue runs", "runs": runs}, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv[1])
