# Receiver hang diagnostics

This separate build helps investigate intermittent receiver freezes while retaining the existing input behavior. It does not fix an unidentified native/driver hang, release a port held by an older process, or change Click Lengthen or other BL4 mods.

## Starting the separate build

Use **Simple Controller Receiver (Hang Diagnostics).exe**, or its matching desktop shortcut. Keep only one receiver running. If Windows cannot finish ending the old receiver and it still owns UDP 42734, restart Windows before using this build. TCP 42735 and 42736 are the existing local face/voice endpoints.

The diagnostic entrypoint enables `SC_RECEIVER_TIMING=1` for its own process. Its default directory is `hang-diagnostics` beside the executable. An explicit `SC_RECEIVER_TIMING_DIR` overrides that directory. Normal receiver imports keep diagnostics disabled unless explicitly enabled.

Each launch creates uniquely named diagnostic files. Rolling retention applies only to files created by that recorder session; it does not scan or clean older sessions, the old `timing-logs` directory, ordinary receiver logs, or user files. Older sessions remain available for investigation and are not automatically deleted. Consequently, disk use across many launches can exceed the per-session limit.

Defaults retain up to **64 MiB per launch**, in four segments of up to 16 MiB. Once full, the oldest segment belonging to that same launch is removed to retain recent activity. The watchdog checks every 250 ms, observes operations lasting at least one second, and repeats an unchanged long-operation capture no more than every ten seconds. Idle heartbeats occur every 30 seconds. Stack snapshots and in-memory queues have fixed limits.

## What the capture adds

- Active operation markers exist while a call is still running, including its fixed category, monotonic start time, Python thread ID, and native thread ID.
- A watchdog observes those markers without acquiring the receiver output lock. It captures bounded Python stack locations for sustained operations, before they return, rather than relying only on completed duration measurements.
- Lock acquisition and lock holding are observed separately. Mouse, keyboard, gamepad, logging/console, startup, and shutdown stages help locate the wait.
- Rolling timing records retain recent activity instead of stopping permanently when an overnight idle log fills. Idle summaries are reduced.
- Metadata identifies the process and provides UTC/monotonic clock correlation.

The additional diagnostics record categories and code locations, not command payloads, pressed keys, pointer coordinates, typed text, stack locals, or source line contents. Existing ordinary receiver logs retain their historical behavior and may contain input commands; they are not the privacy-filtered diagnostic log.

This does not identify clicks originating in Unified Remote or monitor that application. Correlate the receiver capture with the reported time of switching apps/right-clicking; a receiver stall record alone does not attribute causation to another program.

## Interpreting a long operation

An idle `udp_receive_wait` is normal and does not trigger a stall alert by itself. A long command can be an intentional WAIT. A watchdog record is evidence of an operation still in progress, not a diagnosis or a reason to terminate anything. The watchdog never resets controls, cancels commands, or kills processes.

Nested durations overlap and must not be added together as independent CPU time. Python stacks show the call site entering a native API, not the native/kernel stack below it. If the interpreter, Windows, or disk is itself blocked, this in-process capture may also be unable to progress. A native stack capture may still be required to distinguish Windows input, ViGEm, networking, or another driver. This build does not claim to eliminate that limitation.

Explicit shutdown stages are captured before the recorder closes. Later interpreter finalization or native ViGEm destructor work may occur after capture stops; this build cannot observe those stages or continue recording after Windows terminates its threads.

For the reported incident, useful stages include releases sent when leaving the Android app, native mouse/keyboard output, lock waiting, and shutdown. There is no need to disable Click Lengthen to collect this capture. First confirm the new receiver actually controls the PC and its diagnostic files update during use.

## Preservation and verification

The patch does not change packet ordering, ownership, Hold/Turbo, release semantics, WAIT behavior, native output values, or the phone protocol. Existing shutdown/recovery weaknesses are observed rather than silently rewritten.

Tests substitute fake keyboard/mouse/gamepad modules before importing receiver code. They use isolated test files and fake sockets for the instrumented paths. No connected Android tests, phone installs, driver changes, mod changes, or live input generation belong to this validation.

`build_timing_receiver.ps1` requires separate, nonexistent `-DeliveryDirectory` and `-ScratchDirectory` paths. Reviewed source, EXE, and reports go in the delivery; intermediate build/cache/spec/temp files go in scratch. `verify_timing_bundle.py` compares the packaged Python code and both ViGEm DLLs to the reviewed build inputs without launching the executable. Test/build success does not establish real-game performance or prove capture of the original intermittent Windows hang.

The November 10, 2026 patch passed 294 receiver tests, including 27 recorder tests and two fake-native integration scenarios. Deliberately blocked keyboard/mouse/gamepad/log operations and output-lock waits were observed before they completed. Rotation, privacy, bounded I/O behavior, and transient watchdog failure recovery were checked. An independent AST comparison found no non-diagnostic input/protocol behavior changes. A synthetic 50,000-iteration benchmark averaged about 4.5 microseconds for an observed lock plus a fake native-output span on this PC; that is not a measurement of gameplay latency or the complete receiver workload.
