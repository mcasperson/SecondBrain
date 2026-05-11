#!/usr/bin/env python3
"""Analyze operation durations from Octopus server task log."""

import re
from collections import defaultdict

LOG_FILE = "/var/home/matthewcasperson/Downloads/ServerTasks-3128081.log.txt"

# Pattern: "Operation Cached <type> result for <OPERATION> took <DURATION> ms"
PATTERN = re.compile(
    r"WARNING: Operation Cached \w+ result for (.+?) took (\d+) ms"
)

# Pattern: "Total time spent waiting to acquire Cosmos locks: ... (<ms>ms)"
COSMOS_PATTERN = re.compile(
    r"Total time spent waiting to acquire Cosmos locks: .+?\(([0-9,]+)ms\)"
)

durations = defaultdict(list)
cosmos_times = []

with open(LOG_FILE, "r") as f:
    for line in f:
        m = PATTERN.search(line)
        if m:
            operation = m.group(1)
            ms = int(m.group(2))
            durations[operation].append(ms)

        cm = COSMOS_PATTERN.search(line)
        if cm:
            cosmos_ms = int(cm.group(1).replace(",", ""))
            cosmos_times.append(cosmos_ms)

print(f"{'Operation':<60} {'Count':>6} {'Avg (min)':>10} {'Min (min)':>10} {'Max (min)':>10}")
print("-" * 100)

for op in sorted(durations, key=lambda k: sum(durations[k]) / len(durations[k]), reverse=True):
    times = durations[op]
    avg = sum(times) / len(times) / 60000
    mn = min(times) / 60000
    mx = max(times) / 60000
    print(f"{op:<60} {len(times):>6} {avg:>10.2f} {mn:>10.2f} {mx:>10.2f}")

print("-" * 100)
print(f"Total unique operations: {len(durations)}")
print(f"Total warnings: {sum(len(v) for v in durations.values())}")

print()
print("=== Cosmos Lock Wait Times ===")
if cosmos_times:
    avg_c = sum(cosmos_times) / len(cosmos_times) / 60000
    min_c = min(cosmos_times) / 60000
    max_c = max(cosmos_times) / 60000
    print(f"  Count: {len(cosmos_times)}")
    print(f"  Avg:   {avg_c:.2f} min")
    print(f"  Min:   {min_c:.2f} min")
    print(f"  Max:   {max_c:.2f} min")
else:
    print("  No Cosmos lock entries found.")
