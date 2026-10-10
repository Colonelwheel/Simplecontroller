"""Compile the pasteable C#5 sender with a fake VA proxy; contact only a mock TCP server.

Every run uses a new temporary output directory and preserves it for inspection.
It does not launch VoiceAttack, import profiles, load the receiver, or use port 42736.
"""
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent
    builds = root / ".mock-builds"
    builds.mkdir(exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="run-", dir=builds))
    sender = (root / "VoiceAttackSender.cs").read_text(encoding="utf-8")
    sender = sender.replace(
        "private const int Port = 42736;",
        "private static int Port { get { return SenderMockServer.Port; } }",
    ).replace(
        "public class VAInline\n{",
        "public class VAInline\n{\n    public dynamic VA; // Harness-only injection; VoiceAttack supplies this.",
    )
    harness = (root / "SenderMockHarness.cs").read_text(encoding="utf-8")
    sender = sender.replace("using System;", "using System.Threading;\nusing System;", 1)
    harness = harness.replace("using System.Threading;", "", 1)
    combined = output / "SenderMockCombined.cs"
    combined.write_text(sender + "\n" + harness, encoding="utf-8")
    executable = output / "SenderMockHarness.exe"
    compiler = Path(r"C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe")
    command = [str(compiler), "/nologo", "/target:exe", "/main:SenderMockHarness",
               "/out:" + str(executable), "/reference:System.dll",
               "/reference:System.Core.dll", "/reference:Microsoft.CSharp.dll",
               "/reference:System.Web.Extensions.dll", str(combined)]
    print("Isolated outputs:", output, flush=True)
    subprocess.run(command, check=True, timeout=30)
    subprocess.run([str(executable)], check=True, timeout=30)


if __name__ == "__main__":
    main()
