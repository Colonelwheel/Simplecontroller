"""Actual C# sender -> actual stdlib Python voice server and merged pad, fake hardware.

Only a dynamically allocated loopback port is used. No VoiceAttack application,
profile, production receiver entrypoint, native controller, camera, or phone is loaded.
Run with --receiver-dir for an explicit source directory; default is ../staging.
"""
from pathlib import Path
import argparse
import importlib.util
import json
import subprocess
import sys
import tempfile
import threading

sys.dont_write_bytecode = True


class Pad:
    def __init__(self):
        self.buttons = set()
        self.left_trigger = 0.0
        self.right_trigger = 0.0

    def press_button(self, button):
        self.buttons.add(button)

    def release_button(self, button):
        self.buttons.discard(button)

    def left_trigger_float(self, value_float):
        self.left_trigger = value_float

    def right_trigger_float(self, value_float):
        self.right_trigger = value_float

    def update(self):
        pass

    def reset(self):
        self.buttons.clear()
        self.left_trigger = self.right_trigger = 0.0


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


RUNNER = r'''
public static class InteropRunner
{
    public static int Main()
    {
        JavaScriptSerializer json = new JavaScriptSerializer();
        Dictionary<string, SenderMockProxy> chains = new Dictionary<string, SenderMockProxy>();
        string line;
        while ((line = Console.ReadLine()) != null)
        {
            Dictionary<string, object> input = json.DeserializeObject(line) as Dictionary<string, object>;
            string id = (string)input["chain"];
            SenderMockProxy proxy;
            if (!chains.TryGetValue(id, out proxy))
            {
                proxy = new SenderMockProxy();
                chains[id] = proxy;
            }
            proxy.Parameters.Clear();
            object value;
            if (input.TryGetValue("op", out value)) proxy.Parameters["~passedText1"] = value;
            if (input.TryGetValue("button", out value)) proxy.Parameters["~passedText2"] = value;
            if (input.TryGetValue("player", out value)) proxy.Parameters["~passedText3"] = value;
            if (input.TryGetValue("duration", out value)) proxy.Parameters["~passedInteger1"] = Convert.ToInt32(value);
            if (input.TryGetValue("value", out value)) proxy.Parameters["~passedDecimal1"] = Convert.ToDecimal(value);
            int before = proxy.Errors.Count;
            VAInline sender = new VAInline();
            sender.VA = proxy;
            sender.main();
            Console.WriteLine(json.Serialize(new { errors = proxy.Errors.Count - before,
                latest = proxy.Errors.Count == 0 ? "" : proxy.Errors[proxy.Errors.Count - 1] }));
            Console.Out.Flush();
        }
        return 0;
    }
}
'''


def main():
    parser = argparse.ArgumentParser()
    root = Path(__file__).resolve().parent
    staged = root.parent / "staging"
    default_receiver = staged if staged.is_dir() else root.parent
    parser.add_argument("--receiver-dir", type=Path, default=default_receiver)
    args = parser.parse_args()
    # Read/import only the two stdlib modules, never the native receiver entrypoint.
    voice = load("interop_voice_input_server", args.receiver_dir / "voice_input_server.py")
    face = load("interop_face_input_server", args.receiver_dir / "face_input_server.py")
    output_root = root / ".mock-builds"
    output_root.mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="interop-", dir=output_root))
    lock = threading.RLock()
    button_map = {name: name for name in voice.DIGITAL_BUTTONS}
    pads = {p: face.MergedGamepad(Pad(), button_map, lock) for p in ["player1", "player2"]}
    now = [100.0]
    state = None

    def release_all():
        for pad in pads.values():
            pad.reset()
        state.reset(tuple(pads))

    state = voice.VoiceInputState(pads, lock, release_all, clock=lambda: now[0])
    server = voice.VoiceInputServer(state, port=0).start()
    process = None
    count = 0

    def check(condition, message):
        nonlocal count
        if not condition:
            raise AssertionError(message)
        count += 1

    def call(op, button=None, duration=None, value=None, player=None, chain=None, expected_errors=0):
        command = {"chain": chain or "fresh-" + str(count), "op": op}
        for key, item in [("button", button), ("duration", duration), ("value", value), ("player", player)]:
            if item is not None:
                command[key] = item
        process.stdin.write(json.dumps(command) + "\n")
        process.stdin.flush()
        line = process.stdout.readline()
        if not line:
            raise AssertionError("C# sender exited before response: " + process.stderr.read())
        reply = json.loads(line)
        check(reply["errors"] == expected_errors, "Unexpected sender result: " + str(reply))
        return reply

    try:
        sender = (root / "VoiceAttackSender.cs").read_text(encoding="utf-8")
        sender = sender.replace("private const int Port = 42736;", "private const int Port = " + str(server.port) + ";")
        sender = sender.replace("public class VAInline\n{", "public class VAInline\n{\n    public dynamic VA;")
        harness = (root / "SenderMockHarness.cs").read_text(encoding="utf-8")
        proxy = harness[harness.index("public class SenderMockProxy"):harness.index("public static class SenderMockServer")]
        combined = output / "SenderInteropCombined.cs"
        combined.write_text(sender + "\n" + proxy + "\n" + RUNNER, encoding="utf-8")
        executable = output / "SenderInterop.exe"
        subprocess.run([r"C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe", "/nologo",
                        "/target:exe", "/main:InteropRunner", "/out:" + str(executable),
                        "/reference:System.dll", "/reference:System.Core.dll", "/reference:Microsoft.CSharp.dll",
                        "/reference:System.Web.Extensions.dll", str(combined)], check=True, timeout=30)
        process = subprocess.Popen([str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        p1, p2 = pads["player1"], pads["player2"]
        call("tap", "A", 100)
        check("A" in p1.buttons, "Tap presses A")
        now[0] += 0.101
        state.tick()
        check("A" not in p1.buttons, "Receiver owns timed release")
        p1.press_button("B")
        p1.face_set("B", True)
        call("tap", "B", 100)
        now[0] += 0.101
        state.tick()
        check("B" in p1.buttons and "B" not in p1.voice_buttons, "Tap expiry preserves phone and face")
        call("hold", "X", 500)
        now[0] += 0.501
        state.tick()
        check("X" not in p1.buttons, "Finite hold expires")
        call("hold", "Y")
        now[0] += 10
        state.tick()
        check("Y" in p1.buttons, "Persistent hold survives disconnect and time")
        call("release", "Y")
        check("Y" not in p1.buttons, "Persistent hold explicitly released")
        p1.right_trigger_float(0.4)
        call("trigger", "RT", value=0.8)
        check(p1.right_trigger == 0.8, "Trigger merges highest value")
        p1.face_set("RT", True)
        check(p1.right_trigger == 1.0, "Face trigger remains independent")
        p1.face_set("RT", False)
        call("release", "RT")
        check(p1.right_trigger == 0.4, "Release restores phone trigger value")
        p1.right_trigger_float(0.3)
        call("trigger", "RT", value=0.4)
        call("trigger", "RT", 200, 1.0)
        check(p1.right_trigger == 1.0, "Finite trigger overlays persistent voice value")
        now[0] += 0.201
        state.tick()
        check(p1.right_trigger == 0.4, "Finite trigger restores voice baseline above phone")
        call("trigger", "RT", 200, 0.2)
        check(p1.right_trigger == 0.4, "Lower finite overlay does not cut persistent voice baseline")
        call("release", "RT")
        check(p1.right_trigger == 0.3, "Explicit trigger release clears baseline and overlay")
        now[0] += 0.201
        state.tick()
        check(p1.right_trigger == 0.3, "Released overlay deadline cannot restore voice trigger")
        p1.right_trigger_float(0.4)
        call("hold", "RB", player="player2")
        check("RB" in p2.buttons and "RB" not in p1.buttons, "Player 2 separate")
        call("begin", chain="old-clear")
        call("clear")
        check(p1.buttons == {"B"} and not p2.buttons and p1.right_trigger == 0.4, "Voice clear preserves phone/face")
        call("tap", "A", chain="old-clear", expected_errors=1)
        check("A" not in p1.buttons, "Old chain cannot restore after clear")
        call("tap", "A")
        check("A" in p1.buttons, "Fresh chain works after clear")
        call("begin", chain="initial-pause")
        call("trigger", "LT", 500, 0.7)
        check(p1.left_trigger == 0.7, "Finite trigger serialized")
        call("release_all", chain="reset-macro")
        check(not p1.buttons and not p2.buttons and p1.left_trigger == 0 and p1.right_trigger == 0,
              "Global callback clears every owner")
        call("hold", "A", chain="reset-macro", expected_errors=1)
        call("tap", "B", chain="initial-pause", expected_errors=1)
        check(not p1.buttons, "Global reset blocks old macro before first controller action")
        now[0] += 10
        state.tick()
        check(not p1.buttons and p1.left_trigger == 0, "Cancelled timers cannot restore output")
        call("hold", "X")
        check(p1.buttons == {"X"}, "Fresh chain works after global reset")
        call("clear")
        print(f"PASS: {count} actual C# sender/Python server assertions, mocked hardware; ephemeral loopback {server.port}; sources {args.receiver_dir}; outputs {output}")
    finally:
        if process is not None:
            process.stdin.close()
            process.wait(timeout=5)
        server.stop()


if __name__ == "__main__":
    main()
