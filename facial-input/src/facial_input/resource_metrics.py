"""Standalone Windows scalar collector; no camera, input hooks or app control."""
from __future__ import annotations

import argparse
import csv
import ctypes
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import time
from uuid import uuid4

from .telemetry import read_scalars, read_monitor_export


def process_groups(processes, game="", collector_pid=None, seeds=None):
    """Classify full process trees without recording command lines."""
    groups = {pid: group for pid, group in (seeds or {}).items() if any(row["pid"] == pid for row in processes)}
    for process in processes:
        name = process["name"].lower()
        path = process.get("path", "").lower()
        if process["pid"] == collector_pid:
            group = "collector"
        elif "codex" in name or "openai.codex" in path or "\\codex\\app-" in path:
            group = "codex"
        elif "simple controller receiver" in name or "simplecontroller" in name:
            group = "receiver"
        elif "tyler safety monitor" in path or "falling video detection" in path or "fall_detector" in name:
            group = "monitor"
        elif "face detection for gaming inputs" in path or "facial" in name:
            group = "facial"
        elif game and game.lower() in name:
            group = "game"
        else:
            continue
        groups[process["pid"]] = group
    for _ in range(len(processes)):
        changed = False
        for process in processes:
            if process["pid"] not in groups and process["parent"] in groups:
                groups[process["pid"]] = groups[process["parent"]]
                changed = True
        if not changed:
            break
    return groups


def discover_python_groups():
    """One warm-up inventory; retain only IDs/groups, never command lines."""
    if os.name != "nt":
        return {}
    command = "Get-CimInstance Win32_Process | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"
    try:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command],
                                capture_output=True, text=True, timeout=12, creationflags=0x08000000)
        values = json.loads(result.stdout)
        values = [values] if isinstance(values, dict) else values
        seeds = {}
        for value in values:
            line = (value.get("CommandLine") or "").lower()
            if "collect_resources.py" in line:
                continue
            if "face detection for gaming inputs" in line or "facial_input" in line:
                seeds[value["ProcessId"]] = "facial"
            elif "tyler safety monitor" in line or "falling video detection" in line or "fall_detector" in line:
                seeds[value["ProcessId"]] = "monitor"
            elif "simple_controller_receiver" in line:
                seeds[value["ProcessId"]] = "receiver"
        return seeds
    except (OSError, ValueError, TypeError, subprocess.TimeoutExpired):
        return {}


class WindowsSampler:
    def __init__(self):
        if os.name != "nt":
            raise RuntimeError("This collector requires Windows")
        from ctypes import wintypes as w
        self.w = w
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.psapi = ctypes.WinDLL("psapi", use_last_error=True)
        size = ctypes.c_size_t

        class ProcessEntry(ctypes.Structure):
            _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("pid", w.DWORD),
                        ("heap", size), ("module", w.DWORD), ("threads", w.DWORD),
                        ("parent", w.DWORD), ("priority", w.LONG), ("flags", w.DWORD),
                        ("name", w.WCHAR * 260)]

        class Memory(ctypes.Structure):
            _fields_ = [("size", w.DWORD), ("faults", w.DWORD)] + [
                (name, size) for name in ("peak_working", "working", "peak_paged", "paged",
                                         "peak_nonpaged", "nonpaged", "pagefile", "peak_pagefile", "private")]

        class Performance(ctypes.Structure):
            _fields_ = [("size", w.DWORD)] + [(name, size) for name in (
                "commit", "commit_limit", "commit_peak", "physical", "available", "cache",
                "kernel", "kernel_paged", "kernel_nonpaged", "page_size")] + [
                (name, w.DWORD) for name in ("handles", "processes", "threads")]

        class Io(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in (
                "read_count", "write_count", "other_count", "read_bytes", "write_bytes", "other_bytes")]

        self.ProcessEntry, self.Memory, self.Performance, self.Io = ProcessEntry, Memory, Performance, Io
        self.kernel.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
        self.kernel.CreateToolhelp32Snapshot.restype = w.HANDLE
        for name in ("Process32FirstW", "Process32NextW"):
            getattr(self.kernel, name).argtypes = [w.HANDLE, ctypes.POINTER(ProcessEntry)]
            getattr(self.kernel, name).restype = w.BOOL
        self.kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.kernel.OpenProcess.restype = w.HANDLE
        self.kernel.CloseHandle.argtypes = [w.HANDLE]
        self.kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, ctypes.POINTER(w.DWORD)]
        self.kernel.GetProcessTimes.argtypes = [w.HANDLE] + [ctypes.POINTER(w.FILETIME)] * 4
        self.kernel.GetSystemTimes.argtypes = [ctypes.POINTER(w.FILETIME)] * 3
        self.kernel.GetProcessIoCounters.argtypes = [w.HANDLE, ctypes.POINTER(Io)]
        self.psapi.GetProcessMemoryInfo.argtypes = [w.HANDLE, ctypes.POINTER(Memory), w.DWORD]
        self.psapi.GetPerformanceInfo.argtypes = [ctypes.POINTER(Performance), w.DWORD]
        self.previous = {}
        self.previous_system = None
        self.path_cache = {}
        self.count = max(1, os.cpu_count() or 1)
        self.paging = PagingCounters()

    @staticmethod
    def _ticks(value):
        return (value.dwHighDateTime << 32) | value.dwLowDateTime

    def snapshot(self):
        w, kernel = self.w, self.kernel
        now = time.monotonic()
        performance = self.Performance(size=ctypes.sizeof(self.Performance))
        system = {}
        system.update(self.paging.sample())
        if self.psapi.GetPerformanceInfo(ctypes.byref(performance), performance.size):
            page = performance.page_size
            system = {"physical_bytes": performance.physical * page,
                      "available_bytes": performance.available * page,
                      "commit_bytes": performance.commit * page,
                      "commit_limit_bytes": performance.commit_limit * page}
        values = [w.FILETIME() for _ in range(3)]
        if kernel.GetSystemTimes(*[ctypes.byref(value) for value in values]):
            idle, kern, user = [self._ticks(value) for value in values]
            total = kern + user
            if self.previous_system is not None:
                old_idle, old_total = self.previous_system
                difference = total - old_total
                system["cpu_percent"] = 100 * (1 - (idle - old_idle) / difference) if difference > 0 else None
            self.previous_system = idle, total
        rows = []
        handle = kernel.CreateToolhelp32Snapshot(2, 0)
        if handle == ctypes.c_void_p(-1).value:
            return system, rows
        try:
            entry = self.ProcessEntry(size=ctypes.sizeof(self.ProcessEntry))
            more = kernel.Process32FirstW(handle, ctypes.byref(entry))
            while more:
                rows.append({"pid": entry.pid, "parent": entry.parent, "name": entry.name})
                more = kernel.Process32NextW(handle, ctypes.byref(entry))
        finally:
            kernel.CloseHandle(handle)
        current = {}
        live_paths = {}
        for row in rows:
            pid = row["pid"]
            # Limited query permits times/image for more processes. Memory can
            # still be unavailable: represent access failure as null, not zero.
            handle = kernel.OpenProcess(0x1000 | 0x10, False, pid)
            if not handle:
                handle = kernel.OpenProcess(0x1000, False, pid)
            row.update(path="", cpu_percent=None, working_bytes=None, private_bytes=None,
                       faults_delta=None, read_bytes_delta=None, write_bytes_delta=None)
            if not handle:
                continue
            try:
                times = [w.FILETIME() for _ in range(4)]
                identity = None
                ticks = None
                if kernel.GetProcessTimes(handle, *[ctypes.byref(value) for value in times]):
                    identity = (pid, self._ticks(times[0]))
                    row["creation_ticks"] = identity[1]
                    ticks = self._ticks(times[2]) + self._ticks(times[3])
                if identity in self.path_cache:
                    row["path"] = self.path_cache[identity]
                else:
                    path = ctypes.create_unicode_buffer(32768)
                    length = w.DWORD(len(path))
                    if kernel.QueryFullProcessImageNameW(handle, 0, path, ctypes.byref(length)):
                        row["path"] = path.value
                if identity is not None:
                    live_paths[identity] = row["path"]
                memory = self.Memory(size=ctypes.sizeof(self.Memory))
                faults = None
                if self.psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.size):
                    row.update(working_bytes=memory.working, private_bytes=memory.private)
                    faults = memory.faults
                io = self.Io()
                has_io = kernel.GetProcessIoCounters(handle, ctypes.byref(io))
                reads, writes = (io.read_bytes, io.write_bytes) if has_io else (None, None)
                previous = self.previous.get(identity)
                if previous is not None and now > previous[0]:
                    if ticks is not None:
                        row["cpu_percent"] = max(0, (ticks - previous[1]) / 1e7 / (now - previous[0]) * 100 / self.count)
                    for field, new, old in (("faults_delta", faults, previous[2]),
                                            ("read_bytes_delta", reads, previous[3]),
                                            ("write_bytes_delta", writes, previous[4])):
                        if new is not None and old is not None:
                            row[field] = max(0, new - old)
                if identity is not None and ticks is not None:
                    current[identity] = (now, ticks, faults, reads, writes)
            finally:
                kernel.CloseHandle(handle)
        self.previous, self.path_cache = current, live_paths
        return system, rows

    def close(self):
        self.paging.close()


class PagingCounters:
    """Small PDH query; unavailable counters stay null, no system settings edits."""
    PATHS = {"pages_input_per_sec": r"\Memory\Pages Input/sec",
             "pages_output_per_sec": r"\Memory\Pages Output/sec",
             "disk_seconds_per_transfer": r"\PhysicalDisk(_Total)\Avg. Disk sec/Transfer"}

    def __init__(self):
        from ctypes import wintypes as w
        self.api = ctypes.WinDLL("pdh")
        self.query = ctypes.c_void_p()
        self.counters = {}
        self.api.PdhOpenQueryW.argtypes = [w.LPCWSTR, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
        self.api.PdhAddEnglishCounterW.argtypes = [ctypes.c_void_p, w.LPCWSTR, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
        self.api.PdhCollectQueryData.argtypes = [ctypes.c_void_p]
        self.api.PdhCloseQuery.argtypes = [ctypes.c_void_p]
        class Value(ctypes.Structure):
            _fields_ = [("status", w.DWORD), ("value", ctypes.c_double)]
        self.Value = Value
        self.api.PdhGetFormattedCounterValue.argtypes = [ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD), ctypes.POINTER(Value)]
        if self.api.PdhOpenQueryW(None, 0, ctypes.byref(self.query)) != 0:
            self.query = None
            return
        for name, path in self.PATHS.items():
            counter = ctypes.c_void_p()
            if self.api.PdhAddEnglishCounterW(self.query, path, 0, ctypes.byref(counter)) == 0:
                self.counters[name] = counter
        self.api.PdhCollectQueryData(self.query)

    def sample(self):
        values = {name: None for name in self.PATHS}
        if self.query is None or self.api.PdhCollectQueryData(self.query) != 0:
            return values
        for name, counter in self.counters.items():
            value = self.Value()
            if self.api.PdhGetFormattedCounterValue(counter, 0x200, None, ctypes.byref(value)) == 0 and value.status in (0, 1):
                if math.isfinite(value.value) and value.value >= 0:
                    values[name] = value.value
        return values

    def close(self):
        if self.query is not None:
            self.api.PdhCloseQuery(self.query)
            self.query = None


def aggregate(rows, groups):
    result = {}
    for group in ("game", "monitor", "facial", "receiver", "codex", "collector"):
        selected = [row for row in rows if groups.get(row["pid"]) == group]
        result[group] = {"process_count": len(selected), "pids": [row["pid"] for row in selected]}
        for field in ("cpu_percent", "working_bytes", "private_bytes", "faults_delta", "read_bytes_delta", "write_bytes_delta"):
            values = [row[field] for row in selected if row.get(field) is not None]
            result[group][field] = sum(values) if selected and len(values) == len(selected) else None
    return result


def create_run(output_root, label):
    path = Path(output_root) / (datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid4().hex[:8])
    path.mkdir(parents=True, exist_ok=False)
    return path


def frame_time_summary(path):
    """Read bounded scalar columns without modifying an existing capture."""
    values = []
    try:
        with Path(path).open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            column = next((name for name in ("MsBetweenPresents", "DisplayedTime", "MsBetweenDisplayChange")
                           if name in (reader.fieldnames or ())), None)
            if column is None:
                return {"available": False, "reason": "No supported frame-time column"}
            for row in reader:
                try:
                    value = float(row[column])
                except (ValueError, TypeError):
                    continue
                if math.isfinite(value) and value > 0:
                    values.append(value)
                if len(values) >= 1_000_000:
                    break
        if not values:
            return {"available": False, "reason": "No valid frame-time samples"}
        values.sort()
        return {"available": True, "column": column, "frames": len(values), "mean_ms": statistics.fmean(values),
                "p95_ms": values[min(len(values)-1, math.ceil(len(values)*.95)-1)],
                "p99_ms": values[min(len(values)-1, math.ceil(len(values)*.99)-1)], "max_ms": values[-1],
                "over_50_ms": sum(value > 50 for value in values)}
    except OSError as exc:
        return {"available": False, "reason": str(exc)}


def collect(output_root, label="custom", game="", delay=30, warmup=60, duration=300, sampler=None,
            clock=time.monotonic, sleep=time.sleep, scalar_reader=read_scalars, seeds=None, facial_pid=None, presentmon=None,
            monitor_reader=read_monitor_export):
    if any(type(value) not in (int, float) or not 0 <= value <= 86400 for value in (delay, warmup, duration)) or duration <= 0:
        raise ValueError("Invalid collection timing")
    path = create_run(output_root, label)
    metadata = {"label": label, "game_match": game, "collector_pid": os.getpid(),
                "utc": datetime.now(timezone.utc).isoformat(), "delay_seconds": delay,
                "warmup_seconds": warmup, "duration_seconds": duration,
                "frame_times": "PresentMon selected; pending capture" if presentmon else "unavailable: no frame presentation provider selected",
                "monitor_cadence": "Export header cadence/age available only when sharing enabled; actual inference cadence unavailable",
                "limitations": ["Process working-set sums include shared pages and are not total unique RAM.",
                                "Fault counts include soft faults; they do not prove disk paging.",
                                "Result age is not physical face-to-game latency.",
                                "An interval gap identifies a sampling stall, not its cause.",
                                "Python apps without identifiable executable paths may need explicit process IDs."]}
    (path / "run.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    sleep(delay)
    try:
        sampler = sampler or WindowsSampler()
    except Exception as exc:
        (path / "summary.json").write_text(json.dumps({"completed": False, "error": f"{type(exc).__name__}: {exc}",
                                                     "measured_samples": 0}, indent=2), encoding="utf-8")
        return path
    start = clock()
    if seeds is None:
        seeds = discover_python_groups()
    if facial_pid:
        seeds[facial_pid] = "facial"
    previous_at = None
    previous_export = None
    seed_identities = {}
    observations = []
    error = None
    frame_process = None
    frame_error = None
    try:
        with (path / "samples.jsonl").open("x", encoding="utf-8") as stream, (path / "resources.csv").open("x", newline="", encoding="utf-8") as table:
            fields = ["elapsed_seconds", "phase", "sample_gap_seconds", "system_cpu_percent",
                      "available_bytes", "commit_bytes", "commit_limit_bytes"]
            fields += list(PagingCounters.PATHS)
            for group in ("game", "monitor", "facial", "receiver", "codex", "collector"):
                fields += [group + "_" + name for name in ("cpu_percent", "working_bytes", "private_bytes", "faults_delta")]
            fields += ["facial_fps", "facial_result_age_ms", "facial_inference_ms"]
            fields += ["monitor_export_fps", "monitor_export_age_ms"]
            writer = csv.DictWriter(table, fields)
            writer.writeheader()
            while clock() - start < warmup + duration:
                sample_start = clock()
                system, rows = sampler.snapshot()
                for row in rows:
                    pid, creation = row["pid"], row.get("creation_ticks")
                    if pid not in seeds or creation is None:
                        continue
                    if pid not in seed_identities:
                        seed_identities[pid] = creation
                    elif creation != seed_identities[pid]:
                        del seeds[pid]
                groups = process_groups(rows, game, os.getpid(), seeds)
                totals = aggregate(rows, groups)
                facial = next((value for row in rows if groups.get(row["pid"]) == "facial" and (value := scalar_reader(row["pid"])) is not None), None)
                monitor = monitor_reader()
                export_fps = None
                if monitor and previous_export:
                    old_time, old_value = previous_export
                    if monitor["generation"] == old_value["generation"] and sample_start > old_time and monitor["sequence"] >= old_value["sequence"]:
                        export_fps = (monitor["sequence"] - old_value["sequence"]) / (sample_start - old_time)
                previous_export = (sample_start, monitor) if monitor else None
                elapsed = sample_start - start
                phase = "warmup" if elapsed < warmup else "measured"
                if phase == "measured" and presentmon and frame_process is None and frame_error is None:
                    if not Path(presentmon).is_file() or not game or Path(game).name != game:
                        frame_error = "Select an existing PresentMon console executable and an exact game.exe name"
                    else:
                        try:
                            frame_log = (path / "presentmon.log").open("x", encoding="utf-8")
                            try:
                                frame_process = subprocess.Popen([str(presentmon), "--process_name", game,
                                    "--output_file", str(path / "frames.csv"), "--v1_metrics",
                                    "--timed", str(max(1, math.ceil(duration))), "--terminate_after_timed",
                                    "--no_console_stats", "--no_track_input", "--session_name", "FGI-" + uuid4().hex],
                                    stdout=frame_log, stderr=frame_log, creationflags=0x08000000 if os.name == "nt" else 0)
                            finally:
                                frame_log.close()
                        except OSError as exc:
                            frame_error = str(exc)
                gap = None if previous_at is None else sample_start - previous_at
                previous_at = sample_start
                entry = {"elapsed_seconds": elapsed, "phase": phase, "sample_gap_seconds": gap,
                         "system": system, "groups": totals, "facial": facial, "monitor_export": monitor,
                         "processes": [{"pid": row["pid"], "parent": row["parent"], "name": row["name"],
                                        "group": groups.get(row["pid"], "other")} for row in rows if row["pid"] in groups]}
                stream.write(json.dumps(entry, allow_nan=False) + "\n")
                stream.flush()
                record = {"elapsed_seconds": elapsed, "phase": phase, "sample_gap_seconds": gap,
                          "system_cpu_percent": system.get("cpu_percent"),
                          **{key: system.get(key) for key in ("available_bytes", "commit_bytes", "commit_limit_bytes")}}
                record.update({key: system.get(key) for key in PagingCounters.PATHS})
                for group, values in totals.items():
                    record.update({group + "_" + name: values.get(name) for name in ("cpu_percent", "working_bytes", "private_bytes", "faults_delta")})
                record.update({"facial_" + key: (facial or {}).get(source) for key, source in (
                    ("fps", "fps"), ("result_age_ms", "result_age_ms"), ("inference_ms", "inference_ms"))})
                record.update(monitor_export_fps=export_fps, monitor_export_age_ms=(monitor or {}).get("frame_age_ms"))
                writer.writerow(record)
                table.flush()
                if phase == "measured":
                    observations.append(record)
                sleep(max(.01, 1 - (clock() - sample_start)))
    except Exception as exc:
        error = f"{type(exc).__name__}: {exc}"
    finally:
        if hasattr(sampler, "close"):
            sampler.close()
        if frame_process is not None:
            try:
                frame_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                frame_process.terminate()  # Only the collector's own child.
                try:
                    frame_process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    frame_process.kill()
                frame_error = "PresentMon exceeded its bounded capture deadline"
            if frame_process.returncode:
                frame_error = f"PresentMon exit {frame_process.returncode}; inspect presentmon.log"
    summary = {"completed": error is None, "error": error, "measured_samples": len(observations),
               "sampling_stall_intervals": sum((row["sample_gap_seconds"] or 0) > 2 for row in observations), "metrics": {},
               "frame_times": frame_time_summary(path / "frames.csv") if frame_process and not frame_error else
                   {"available": False, "reason": frame_error or "No PresentMon console executable selected"}}
    if observations:
        for key in observations[0]:
            values = [row[key] for row in observations if type(row[key]) in (int, float)]
            if values:
                summary["metrics"][key] = {"mean": statistics.fmean(values), "max": max(values), "samples": len(values)}
    (path / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--delay", type=float, default=30)
    parser.add_argument("--warmup", type=float, default=60)
    parser.add_argument("--duration", type=float, default=300)
    parser.add_argument("--label", default="custom")
    parser.add_argument("--game", default="")
    parser.add_argument("--facial-pid", type=int)
    parser.add_argument("--presentmon", type=Path)
    parser.add_argument("--output-root", type=Path, required=True)
    args = parser.parse_args()
    collect(**vars(args))


if __name__ == "__main__":
    main()
