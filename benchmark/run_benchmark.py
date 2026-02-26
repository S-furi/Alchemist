#!/usr/bin/env python3
import argparse
import csv
import os
import re
import statistics
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from typing import Any

try:
    import psutil

    PSUTIL_AVAILABLE = True
except ImportError:
    PSUTIL_AVAILABLE = False
    print("Warning: 'psutil' not found. Memory monitoring will be skipped.")
    print("Install it via: pip install psutil")


BENCHMARK_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.abspath(os.path.join(BENCHMARK_DIR, ".."))
MASTER_WORKTREE = os.path.abspath(
    os.path.join(REPO_DIR, "..", "alchemist-benchmark-master")
)
SCENARIOS = [
    "independent.yml",
    "independent_scafi.yml",
    "interdependent.yml",
    "biochem_stress.yml",
]
DEFAULT_RUNS = 1


def run_command(command: str, cwd: str | None = None, verbose: bool = False) -> str:
    try:
        if verbose:
            print(f"Running: {command}")
        result = subprocess.run(
            command,
            cwd=cwd,
            shell=True,
            check=True,
            stdout=subprocess.PIPE if not verbose else None,
            stderr=subprocess.PIPE if not verbose else None,
            text=True,
        )
        return result.stdout.strip() if result.stdout else ""
    except subprocess.CalledProcessError as e:
        print(f"Error in command: {command}")
        if not verbose:
            if e.stdout:
                print(e.stdout)
            if e.stderr:
                print(e.stderr)
        raise e


def find_jar(directory: str) -> str | None:
    build_dir = os.path.join(directory, "build")
    if not os.path.exists(build_dir):
        return None
    for root, _, files in os.walk(build_dir):
        for f in files:
            if f.endswith("-all.jar"):
                return os.path.join(root, f)
    return None


def get_or_build_jar(directory: str, label: str, force_build: bool = False) -> str:
    jar = find_jar(directory)
    if not jar or force_build:
        print(f"Building {label} ShadowJar...")
        run_command("./gradlew shadowJar -x test", cwd=directory, verbose=True)
        jar = find_jar(directory)
    else:
        print(f"{label} JAR found: {jar}")

    if not jar:
        raise FileNotFoundError(
            f"Failed to find or build JAR for {label} in {directory}"
        )
    return jar


def setup_master_worktree(
    commit_hash: str = "037d7ec49e3234b9952f3f5fc574c72266d8721a",
) -> None:
    if not os.path.exists(MASTER_WORKTREE):
        print(f"Setting up Master worktree at {MASTER_WORKTREE}...")
        run_command("git worktree prune")
        run_command("git fetch")
        run_command(f"git worktree add {MASTER_WORKTREE} {commit_hash}")


def monitor_memory(proc: subprocess.Popen, skip_samples: int = 5) -> float:
    samples = []
    if not PSUTIL_AVAILABLE:
        return 0.0

    try:
        ps_proc = psutil.Process(proc.pid)
        while proc.poll() is None:
            try:
                current_rss = ps_proc.memory_info().rss
                for child in ps_proc.children(recursive=True):
                    current_rss += child.memory_info().rss
                samples.append(current_rss)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                pass
            time.sleep(1.0)
    except psutil.NoSuchProcess:
        pass

    if not samples:
        return 0.0

    if len(samples) > skip_samples: # skip ramp up
        relevant_samples = samples[skip_samples:]
    else:
        relevant_samples = [samples[-1]]

    return statistics.mean(relevant_samples) / (1024 * 1024)  # mb


def extract_duration(output: str) -> float | None:
    start_match = re.search(r"(\d{2}:\d{2}:\d{2}\.\d{3}).*Starting engine", output)
    end_match = re.search(
        r"(\d{2}:\d{2}:\d{2}\.\d{3}).*Termination condition reached", output
    )

    if start_match and end_match:
        fmt = "%H:%M:%S.%f"
        t_start = datetime.strptime(start_match.group(1), fmt)
        t_end = datetime.strptime(end_match.group(1), fmt)
        duration = (t_end - t_start).total_seconds()
        if duration < 0:
            duration += 86400  # midnight rollover
        return duration
    return None


def run_benchmark_scenario(
    engine_name: str, jar_path: str, scenario_path: str, jvm_flags: str, runs: int
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    times = []
    mems = []
    scenario_results = []

    print(f"Testing {engine_name} on {os.path.basename(scenario_path)}...")

    for i in range(1, runs + 1):
        print(f"  Run {i}/{runs}...", end="", flush=True)
        try:
            cmd = f'java {jvm_flags} -jar "{jar_path}" run "{scenario_path}" --verbosity info'
            with tempfile.TemporaryFile(mode="w+") as tmp:
                proc = subprocess.Popen(
                    cmd, shell=True, stdout=tmp, stderr=subprocess.STDOUT, text=True
                )

                avg_mb = monitor_memory(proc)
                proc.wait()
                tmp.seek(0)
                output = tmp.read()

            if "ERROR" in output or proc.returncode != 0:
                print(f"\nSimulation failed for {engine_name} on {scenario_path}:")
                print(output)
                print("ABORTING.")
                sys.exit(1)

            duration = extract_duration(output)
            if duration is not None:
                times.append(duration)
                mems.append(avg_mb)
                print(f" {duration:.2f}s, Avg Mem: {avg_mb:.2f} MB")
                scenario_results.append(
                    {
                        "Engine": engine_name,
                        "Scenario": os.path.basename(scenario_path),
                        "Run": i,
                        "Time(s)": duration,
                        "AvgMem(MB)": avg_mb,
                    }
                )
            else:
                print(" FAILED (Markers not found in logs)")
        except Exception as e:
            print(f" FAILED ({e})")

    summary = {}
    if times:
        summary = {
            "Engine": engine_name,
            "Scenario": os.path.basename(scenario_path),
            "AvgTime": statistics.mean(times),
            "MedianTime": statistics.median(times),
            "BestTime": min(times),
            "WorstTime": max(times),
            "AvgMem": statistics.mean(mems) if mems else 0,
            "MaxMem": max(mems) if mems else 0,
            "AllRuns": "|".join([f"{t:.2f}" for t in times]),
        }

    return scenario_results, summary


def save_results(
    results: list[dict[str, Any]], summary: list[dict[str, Any]], timestamp: str
):
    results_file = os.path.join(BENCHMARK_DIR, f"results_{timestamp}.csv")
    summary_file = os.path.join(BENCHMARK_DIR, f"summary_{timestamp}.csv")

    if results:
        with open(results_file, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=results[0].keys())
            writer.writeheader()
            writer.writerows(results)
        print(f"\nResults saved to: {results_file}")

    if summary:
        with open(summary_file, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=summary[0].keys())
            writer.writeheader()
            writer.writerows(summary)
        print(f"Summary saved to: {summary_file}")


def print_summary_table(summary: list[dict[str, Any]]):
    print("\n" + "=" * 110)
    print(
        f"{'Engine':<10} | {'Scenario':<20} | {'Avg Time':<10} | {'Best':<8} | {'Avg Mem':<10} | {'Max Mem':<10}"
    )
    print("-" * 110)
    for r in summary:
        print(
            f"{r['Engine']:<10} | {r['Scenario']:<20} | {r['AvgTime']:<10.2f} | "
            f"{r['BestTime']:<8.2f} | {r['AvgMem']:<10.2f} | {r['MaxMem']:<10.2f}"
        )
    print("=" * 110)


def cleanup():
    print("Cleaning up...")
    if os.path.exists(MASTER_WORKTREE):
        subprocess.run(f"rm -rf {MASTER_WORKTREE}", shell=True)
        subprocess.run("git worktree prune", shell=True)


def parse_args():
    parser = argparse.ArgumentParser(description="Alchemist Benchmark Runner")
    parser.add_argument(
        "--cleanup",
        action="store_true",
        help="Remove worktree and temp files after benchmark",
    )
    parser.add_argument("--build", action="store_true", help="Force rebuild of JARs")
    parser.add_argument(
        "-s",
        "--scenario",
        type=str,
        help="Comma separated list of yml files to add to scenarios",
    )
    parser.add_argument(
        "--compactHeaders",
        action="store_true",
        help="Enable JVM flags for JEP 450 (Compact Object Headers)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=DEFAULT_RUNS,
        help=f"Number of runs per scenario (default: {DEFAULT_RUNS})",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    scenarios_to_run = (
        [s.strip() for s in args.scenario.split(",")]
        if args.scenario
        else list(SCENARIOS)
    )
    scenarios_to_run = list(dict.fromkeys(scenarios_to_run))

    if not os.path.exists(BENCHMARK_DIR):
        os.makedirs(BENCHMARK_DIR)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    try:
        setup_master_worktree()

        master_jar = get_or_build_jar(MASTER_WORKTREE, "Classic", args.build)
        reactive_jar = get_or_build_jar(REPO_DIR, "Reactive", args.build)

        all_results = []
        all_summaries = []

        jvm_flags = "-Djava.awt.headless=true"
        if args.compactHeaders:
            jvm_flags += (
                " -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders"
            )
            print("Compact object headers enabled...")

        for s in scenarios_to_run:
            path = os.path.join(BENCHMARK_DIR, s)
            if not os.path.exists(path):
                print(f"Warning: Scenario file not found: {path}")
                continue

            for engine_name, jar in [
                ("Classic", master_jar),
                ("Reactive", reactive_jar),
            ]:
                res, summ = run_benchmark_scenario(
                    engine_name, jar, path, jvm_flags, args.runs
                )
                all_results.extend(res)
                if summ:
                    all_summaries.append(summ)

        save_results(all_results, all_summaries, timestamp)
        print_summary_table(all_summaries)

    finally:
        if args.cleanup:
            cleanup()


if __name__ == "__main__":
    main()
