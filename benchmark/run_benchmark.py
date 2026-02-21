#!/usr/bin/env python3
import os
import subprocess
import time
import csv
import statistics
import argparse
import sys
import re
from datetime import datetime
from collections import defaultdict

try:
    from scipy import stats
    SCIPY_AVAILABLE = True
except ImportError:
    SCIPY_AVAILABLE = False
    print("Warning: 'scipy' not found. Statistical tests will be skipped.")
    print("Install it via: pip install scipy")

REPO_DIR = os.getcwd()
MASTER_WORKTREE = os.path.abspath(os.path.join(REPO_DIR, "../alchemist-benchmark-master"))
BENCHMARK_DIR = os.path.join(REPO_DIR, "benchmark")
RESULTS_FILE = os.path.join(BENCHMARK_DIR, "results.csv")
SUMMARY_FILE = os.path.join(BENCHMARK_DIR, "summary.csv")
# beware, running all four tests will take a LOT of time, especially for 10 runs each
SCENARIOS = ["independent.yml", "independent_scafi.yml", "interdependent.yml", "biochem_stress.yml"]
RUNS = 6 # runs must be > 6 or wilcoxon test will not have enough tests to compute properly

def run_command(command, cwd=None, verbose=False):
    try:
        if verbose: print(f"Running: {command}")
        result = subprocess.run(
            command, cwd=cwd, shell=True, check=True,
            stdout=subprocess.PIPE if not verbose else None,
            stderr=subprocess.PIPE if not verbose else None, text=True
        )
        return result.stdout.strip() if result.stdout else ""
    except subprocess.CalledProcessError as e:
        print(f"Error in command: {command}")
        if not verbose:
            if e.stdout: print(e.stdout)
            if e.stderr: print(e.stderr)
        raise e

def find_jar(directory: str):
    for root, _, files in os.walk(os.path.join(directory, "build")):
        for f in files:
            if f.endswith("-all.jar"):
                return os.path.join(root, f)
    return None

def perform_statistical_test(name_a, times_a, name_b, times_b):
    """
    Performs a Wilcoxon Signed-Rank test for evaluating which model is best.
    Probably not needed as the average time is quite self explainatory ;)
    """
    if not SCIPY_AVAILABLE or not times_a or not times_b:
        return float('nan'), "N/A"

    if times_a == times_b:
        return 1.0, "Identical"

    try:
        _, p_value = stats.wilcoxon(times_a, times_b, alternative='two-sided')
    except ValueError as e:
        return 1.0, "Error/Identical"

    verdict = "Inconclusive"
    alpha = 0.05

    if p_value < alpha:
        diffs = [b - a for a, b in zip(times_a, times_b)]
        positive_diffs = sum(1 for d in diffs if d > 0)
        negative_diffs = sum(1 for d in diffs if d < 0)

        if positive_diffs > negative_diffs:
             verdict = f"{name_a} faster"
        else:
             verdict = f"{name_b} faster"
    else:
        verdict = "No significant diff"

    return p_value, verdict

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cleanup", action="store_true", help="Remove worktree and temp files after benchmark")
    parser.add_argument("--build", action="store_true", help="Force rebuild of JARs")
    args = parser.parse_args()

    if not os.path.exists(BENCHMARK_DIR): os.makedirs(BENCHMARK_DIR)

    try:
        if not os.path.exists(MASTER_WORKTREE):
            print("Setting up Master worktree...")
            run_command("git worktree prune")
            run_command(f"git worktree add {MASTER_WORKTREE} master")

        def get_or_build(dir, label):
            jar = find_jar(dir)
            if not jar or args.build:
                print(f"Building {label} ShadowJar...")
                run_command("./gradlew shadowJar -x test", cwd=dir, verbose=True)
                jar = find_jar(dir)
            else:
                print(f"{label} JAR found: {jar}")
            return jar

        master_jar = get_or_build(MASTER_WORKTREE, "Classic")
        reactive_jar = get_or_build(REPO_DIR, "Reactive")

        results = []
        summary = []
        
        benchmark_data = defaultdict(dict)

        for s in SCENARIOS:
            path = os.path.join(BENCHMARK_DIR, s)
            if not os.path.exists(path): continue

            for engine_name, jar in [("Classic", master_jar), ("Reactive", reactive_jar)]:
                print(f"Testing {engine_name} on {s}...")
                times = []
                for i in range(1, RUNS + 1):
                    print(f"  Run {i}/{RUNS}...", end="", flush=True)
                    try:
                        cmd = f"java -Djava.awt.headless=true -jar \"{jar}\" run \"{path}\" --verbosity info"
                        res = subprocess.run(
                            cmd, shell=True, check=False,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
                        )
                        output = res.stdout
                        if "ERROR" in output or res.returncode != 0:
                            print(f"\nSimulation failed for {engine_name} on {s}:")
                            print(output)
                            print("ABORTING.")
                            sys.exit(1)

                        start_match = re.search(r"(\d{2}:\d{2}:\d{2}\.\d{3}).*Starting engine", output)
                        end_match = re.search(r"(\d{2}:\d{2}:\d{2}\.\d{3}).*Termination condition reached", output)

                        if start_match and end_match:
                            fmt = "%H:%M:%S.%f"
                            t_start = datetime.strptime(start_match.group(1), fmt)
                            t_end = datetime.strptime(end_match.group(1), fmt)
                            duration = (t_end - t_start).total_seconds()
                            if duration < 0: duration += 86400 # handle midnight
                        else:
                            print(" FAILED (Markers not found in logs)")
                            continue

                        times.append(duration)
                        print(f" {duration:.2f}s")
                        results.append([engine_name, s, i, duration])
                    except Exception as e:
                        print(f" FAILED ({e})")

                if times:
                    benchmark_data[s][engine_name] = times
                    
                    summary.append([
                        engine_name, 
                        s, 
                        statistics.mean(times), 
                        statistics.median(times),
                        min(times), 
                        max(times),
                        "|".join([f"{t:.2f}" for t in times])
                    ])

        with open(RESULTS_FILE, 'w') as f:
            w = csv.writer(f)
            w.writerow(["Engine", "Scenario", "Run", "Time(s)"])
            w.writerows(results)

        with open(SUMMARY_FILE, 'w') as f:
            w = csv.writer(f)
            w.writerow(["Engine", "Scenario", "Avg", "Median", "Best", "Worst", "AllRuns"])
            w.writerows(summary)

        print("\n" + "="*95)
        print(f"{'Engine':<10} | {'Scenario':<20} | {'Avg':<8} | {'Median':<8} | {'Best':<8} | {'Worst':<8}")
        print("-" * 95)
        for r in summary:
            print(f"{r[0]:<10} | {r[1]:<20} | {r[2]:<8.2f} | {r[3]:<8.2f} | {r[4]:<8.2f} | {r[5]:<8.2f}")
        print("=" * 95)

        if SCIPY_AVAILABLE:
            print("\nStatistical Comparison (Classic vs Reactive) [Mann-Whitney U Test]")
            print(f"{'Scenario':<20} | {'p-value':<10} | {'Verdict':<20}")
            print("-" * 60)
            
            for s in SCENARIOS:
                if "Classic" in benchmark_data[s] and "Reactive" in benchmark_data[s]:
                    t_classic = benchmark_data[s]["Classic"]
                    t_reactive = benchmark_data[s]["Reactive"]
                    
                    p_val, verdict = perform_statistical_test("Classic", t_classic, "Reactive", t_reactive)
                    
                    p_str = f"{p_val:.4f}" if not isinstance(p_val, float) or p_val > 0.0001 else "<0.0001"
                    print(f"{s:<20} | {p_str:<10} | {verdict:<20}")
            print("-" * 60)

    finally:
        if args.cleanup:
            print("Cleaning up...")
            if os.path.exists(MASTER_WORKTREE):
                subprocess.run(f"rm -rf {MASTER_WORKTREE}", shell=True)
                subprocess.run("git worktree prune", shell=True)

if __name__ == "__main__":
    main()
