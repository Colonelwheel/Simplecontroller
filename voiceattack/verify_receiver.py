"""Run only mocked receiver tests with new, isolated temporary/log outputs."""
from pathlib import Path
import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path,
                        default=root / "staging" if (root / "staging").exists() else root)
    parser.add_argument("--facial-client-root", type=Path,
                        help="Optional existing facial project for its mocked client/runtime interoperability tests.")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        import unittest
        source = args.source.resolve()
        sys.path.insert(0, str(source))
        suite = unittest.defaultTestLoader.discover(str(source), pattern="test_*.py")
        if args.facial_client_root:
            for name in ("test_client_protocol", "test_runtime_protocol"):
                test_path = args.facial_client_root / "integrations" / "receiver" / (name + ".py")
                spec = importlib.util.spec_from_file_location(name, test_path)
                module = importlib.util.module_from_spec(spec)
                sys.modules[name] = module
                spec.loader.exec_module(module)
                suite.addTests(unittest.defaultTestLoader.loadTestsFromModule(module))
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    artifact_root = root / ".test-artifacts"
    artifact_root.mkdir(exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix="receiver-", dir=artifact_root))
    environment = dict(os.environ, TMP=str(scratch), TEMP=str(scratch),
                       LOCALAPPDATA=str(scratch), PYTHONDONTWRITEBYTECODE="1")
    print("Mock-only tests; isolated logs/temp: " + str(scratch), flush=True)
    log = scratch / "tests.log"
    command = [sys.executable, "-B", str(Path(__file__).resolve()), "--worker",
               "--source", str(args.source.resolve())]
    if args.facial_client_root:
        command += ["--facial-client-root", str(args.facial_client_root.resolve())]
    with log.open("x", encoding="utf-8") as output:
        result = subprocess.run(
            command, cwd=root, env=environment,
            stdout=output, stderr=subprocess.STDOUT)
    lines = log.read_text(encoding="utf-8").splitlines()
    print("\n".join(lines[-40:] if result.returncode else lines[-8:]))
    print("Full test log: " + str(log))
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
