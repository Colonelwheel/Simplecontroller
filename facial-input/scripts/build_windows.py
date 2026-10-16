"""Build a self-contained Windows GUI using the existing isolated Python environment."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

MODEL_SHA256 = '64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff'
PROJECT = Path(__file__).resolve().parents[1]


def collect_notices(destination):
    """Copy installed notice files only; never copy runtime settings or environments."""
    destination.mkdir(parents=True, exist_ok=True)
    versions = {}
    for dist in importlib.metadata.distributions():
        name = dist.metadata.get('Name', 'unknown')
        versions[name] = {'version': dist.version,
                          'license': dist.metadata.get('License-Expression') or dist.metadata.get('License', '')}
        for entry in dist.files or ():
            path = Path(str(entry))
            if not any(token in path.name.upper() for token in ('LICENSE', 'COPYING', 'NOTICE', 'COPYRIGHT')):
                continue
            source = Path(dist.locate_file(entry))
            if not source.is_file():
                continue
            # Some wheels use ../Scripts; retain notices inside a bounded target.
            safe_parts = [part for part in path.parts if part not in ('.', '..') and ':' not in part]
            target = destination / name / Path(*safe_parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    python_license = Path(sys.base_prefix) / 'LICENSE.txt'
    if python_license.is_file():
        shutil.copy2(python_license, destination / 'Python-LICENSE.txt')
    (destination / 'components.json').write_text(json.dumps(versions, indent=2), encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-root', type=Path, default=PROJECT / 'deliveries' / 'windows-app')
    parser.add_argument('--tools-dir', type=Path, help='Isolated --target install of requirements-build.txt')
    parser.add_argument('--model', type=Path, default=PROJECT / 'models' / 'face_landmarker.task')
    parser.add_argument('--replace-build-output', action='store_true',
                        help='Explicitly allow replacing an existing FacialInput.exe in this output folder')
    args = parser.parse_args(argv)
    if sys.platform != 'win32' or sys.version_info[:2] != (3, 12):
        parser.error('Build with Windows x64 Python 3.12 in the project environment.')
    model = args.model.resolve()
    if not model.is_file() or hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA256:
        parser.error('The official Face Landmarker model is missing or its SHA-256 does not match.')
    output = args.output_root.resolve()
    if output == PROJECT or output in PROJECT.parents:
        parser.error('Choose a dedicated generated-output directory.')
    executable = output / 'dist' / 'FacialInput.exe'
    if executable.exists() and not args.replace_build_output:
        parser.error('EXE already exists; preserved. Choose a new output root or explicitly use --replace-build-output.')
    output.mkdir(parents=True, exist_ok=True)
    notices = output / 'licenses'
    collect_notices(notices)
    environment = os.environ.copy()
    if args.tools_dir:
        tools = args.tools_dir.resolve()
        if not (tools / 'PyInstaller').is_dir():
            parser.error('The tools directory does not contain PyInstaller.')
        environment['PYTHONPATH'] = str(tools)
    environment['PYTHONDONTWRITEBYTECODE'] = '1'
    environment['PYINSTALLER_CONFIG_DIR'] = str(output / 'cache')
    environment['FACIAL_MODEL_FILE'] = str(model)
    environment['FACIAL_LICENSE_DIRECTORY'] = str(notices)
    environment['MPLBACKEND'] = 'Agg'
    # Do not discover unrelated DLLs from developer tools on the host PATH.
    windows = Path(environment.get('SystemRoot', r'C:\Windows'))
    environment['PATH'] = os.pathsep.join(str(path) for path in
        (Path(sys.executable).parent, Path(sys.base_prefix), Path(sys.base_prefix) / 'DLLs',
         windows / 'System32', windows))
    inputs = sorted((PROJECT / 'src' / 'facial_input').glob('*.py'))
    inputs += [PROJECT / 'scripts' / 'start_gui.py', PROJECT / 'packaging' / 'FacialInput.spec']
    before = {path.relative_to(PROJECT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in inputs}
    command = [sys.executable, '-B', '-m', 'PyInstaller', '--noconfirm',
               '--distpath', str(output / 'dist'), '--workpath', str(output / 'work'),
               str(PROJECT / 'packaging' / 'FacialInput.spec')]
    subprocess.run(command, cwd=PROJECT, env=environment, check=True)
    after = {path.relative_to(PROJECT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
             for path in inputs}
    if before != after:
        raise RuntimeError('Source changed during packaging; rebuild before distributing this EXE.')
    report = {'file': executable.name, 'bytes': executable.stat().st_size,
              'sha256': hashlib.sha256(executable.read_bytes()).hexdigest(),
              'python': sys.version, 'model_sha256': MODEL_SHA256, 'source_sha256': after}
    (output / 'build-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
