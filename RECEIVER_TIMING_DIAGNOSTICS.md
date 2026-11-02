# Separate receiver timing diagnostics

The diagnostic executable uses the verified Floating Controls receiver source and adds timing observations. It does not change packet ordering, ownership, Hold/Turbo behavior, releases, native values, or the phone protocol. The ordinary source entrypoint leaves timing disabled unless explicitly enabled for that process.

## Running the candidate

The distinct `Simple Controller Receiver (Timing Diagnostics).exe` entrypoint enables `SC_RECEIVER_TIMING=1` only in its own process. By default, it creates a new uniquely named JSONL file under `timing-logs` beside the diagnostic executable. An explicit `SC_RECEIVER_TIMING_DIR` overrides this location. It does not overwrite prior timing logs or change persistent settings.

Prepare and inspect the candidate before switching. Close the old receiver before starting the candidate; two receivers must not bind the same port or create competing virtual-controller pairs. Keep the existing receiver and shortcut for recovery. Preparing this build does not authorize replacing the installed EXE, changing its shortcut, stopping it, or launching native outputs automatically.

## What is measured

- `udp_receive_wait`: time spent in the socket receive call. Long values also occur normally while there is no input; they do not establish a connectivity fault.
- `udp_dispatch`: time from returning from `recvfrom` to command dispatch.
- `udp_process` / `command`: packet/command processing duration. Nested command calls overlap; these totals must not be added together as independent CPU time.
- `phone_protocol`: the negotiated phone protocol path, including its existing locking and native output work.
- `lock_wait` / `lock_hold`: wait/hold duration around the receiver's existing `synchronized` callbacks. Direct lock sites are not all separately instrumented.
- `native_gamepad_update`: time inside each merged gamepad's raw native report-update call.
- `desktop_output`, `game_mouse_output`, `legacy_mouse_output`, `scroll_output`: the corresponding existing output/handler durations. Legacy mouse timing includes the existing console debug print, so a slow console write can be distinguished from a slow gamepad report.

Timings are wall-clock durations, not CPU usage. No phone key values, typed characters, commands, coordinates, IP addresses, process/session identities from packets, or payload contents are recorded. Only fixed categories and timing/count statistics enter the diagnostic file.

The input path never formats or writes diagnostic files. It attempts to record into bounded memory without waiting for the recorder's lock; contended samples are counted as dropped. A background writer emits summaries approximately once per second, with bounded slow-event detail for calls taking at least 20 ms. The log is capped at 64 MiB. File/cap failures disable diagnostics while leaving controller processing active.

Shutdown waits no more than 250 ms for the background writer; if storage is blocked, the final flush is best effort. This prevents a diagnostic file write from holding up normal receiver shutdown.

## Interpreting a recurrence

A long `udp_process` with a matching long `native_gamepad_update` points toward the virtual-controller/native call. A long command with long lock wait points toward a receiver lock holder. A long legacy mouse handler with short gamepad calls can implicate its mouse/console path. Short processing after a receive gap or a burst cannot alone distinguish phone-side queueing, network delivery, and packets waiting in Windows' socket buffer.

Receiver read timestamps are not network-adapter arrival timestamps or phone touch/send timestamps. A normal heartbeat receive wait must not be labeled a stall, and this diagnostic does not discard old packets or attempt an unverified queue fix.

## Verification boundary

Tests replace all native input modules before importing the receiver. They use fake outputs and mock sockets, an isolated temporary directory, and controlled native/lock delays. Packaging and static archive/source checks do not prove live game feel, physical controller output, absence of hardware-specific latency, or the recurrence's root cause. Do not run connected Android tests or install/update the primary phone for this receiver-only diagnostic.

The November 2, 2026 candidate passed all 282 receiver/timing tests. A focused review strengthened the enabled integration test to require positive stage counts and wait for its test writer's final flush; all 17 timing-focused tests then passed. Static archive checks verified all eight bundled Python modules against fresh compilation of their delivery sources and both ViGEm DLLs against their original bytes. The EXE was not executed.

The running Floating Controls receiver's SHA-256 was `4CBF88F909E38EDA70ACEA63517D720820FFA31530E2E02D9F6EB10835BFCF18`. It exactly matched the published receiver, whose six embedded modules matched the published source and the floating-controls checkout after newline normalization. The diagnostic source branch starts from commit `18172c3e2599616cd43f223821df83bef87ab0fc`; the existing receiver remains the current working version.

A synthetic benchmark of 50,000 enabled counter records took 17.2 ms on this PC (about 0.34 microseconds per record). That measures counter recording only; it does not establish the full diagnostic receiver's live hardware/game overhead or comfort.
