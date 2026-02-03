#!/usr/bin/env python3
import os
import subprocess
import time
import csv
import statistics
import argparse

REPO_DIR = os.getcwd()
MASTER_WORKTREE = os.path.abspath(os.path.join(REPO_DIR, "../alchemist-benchmark-master"))
BENCHMARK_DIR = os.path.join(REPO_DIR, "benchmark")
RESULTS_FILE = os.path.join(BENCHMARK_DIR, "results.csv")
SUMMARY_FILE = os.path.join(BENCHMARK_DIR, "summary.csv")
SCENARIOS = ["independent.yml", "interdependent.yml"]
RUNS = 10

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

        for s in SCENARIOS:
            path = os.path.join(BENCHMARK_DIR, s)
            if not os.path.exists(path): continue

            for engine_name, jar in [("Classic", master_jar), ("Reactive", reactive_jar)]:
                print(f"Testing {engine_name} on {s}...")
                times = []
                for i in range(1, RUNS + 1):
                    print(f"  Run {i}/{RUNS}...", end="", flush=True)
                    start = time.time()
                    try:
                        subprocess.run(
                            f"java -Djava.awt.headless=true -jar \"{jar}\" run \"{path}\" --verbosity error",
                            shell=True, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                        )
                        duration = time.time() - start
                        times.append(duration)
                        print(f" {duration:.2f}s")
                        results.append([engine_name, s, i, duration])
                    except Exception:
                        print(" FAILED")

                if times:
                    summary.append([
                        engine_name, s, statistics.mean(times), min(times), max(times),
                        "|".join([f"{t:.2f}" for t in times])
                    ])


        with open(RESULTS_FILE, 'w') as f:
            w = csv.writer(f)
            w.writerow(["Engine", "Scenario", "Run", "Time(s)"])
            w.writerows(results)

        with open(SUMMARY_FILE, 'w') as f:
            w = csv.writer(f)
            w.writerow(["Engine", "Scenario", "Avg", "Best", "Worst", "AllRuns"])
            w.writerows(summary)

        print("\n" + "-"*90)
        print(f"{ 'Engine':<10} | { 'Scenario':<20} | { 'Avg':<8} | { 'Best':<8} | { 'Worst':<8}")
        print("-"*90)
        for r in summary:
            print(f"{r[0]:<10} | {r[1]:<20} | {r[2]:<8.2f} | {r[3]:<8.2f} | {r[4]:<8.2f}")
        print("-"*90)

    finally:
        if args.cleanup:
            print("Cleaning up...")
            if os.path.exists(MASTER_WORKTREE):
                subprocess.run(f"rm -rf {MASTER_WORKTREE}", shell=True)
                subprocess.run("git worktree prune", shell=True)

if __name__ == "__main__":
    main()
