param(
    [Parameter(Mandatory=$true)][string]$DeliveryDirectory,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$deliveryRoot = [IO.Path]::GetFullPath($DeliveryDirectory)
if ($DeliveryDirectory -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\[^\\]+(?:\\|$))') {
    throw 'DeliveryDirectory must be an absolute new directory.'
}
if (Test-Path -LiteralPath $deliveryRoot) { throw 'Delivery already exists; refusing overwrite.' }
$pythonCommand = Get-Command -Name $Python -CommandType Application -ErrorAction Stop | Select-Object -First 1
$Python = $pythonCommand.Source
$sourceNames = @('receiver_diagnostics.py','receiver_timing.py','desktop_output.py','simple_controller_receiver.py','phone_output_protocol.py','relative_mouse.py','face_input_server.py','voice_input_server.py','camera_follow.py')
$dllRoot = Join-Path $sourceRoot 'ViGEmClient'
foreach ($name in $sourceNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $name) -PathType Leaf)) { throw "Missing source: $name" }
}
foreach ($architecture in @('x64','x86')) {
    if (-not (Test-Path -LiteralPath (Join-Path $dllRoot "$architecture\ViGEmClient.dll") -PathType Leaf)) { throw "Missing ViGEm DLL: $architecture" }
}
New-Item -ItemType Directory -Path $deliveryRoot | Out-Null
foreach ($name in @('source','build','dist','spec','cache','temp')) {
    New-Item -ItemType Directory -Path (Join-Path $deliveryRoot $name) | Out-Null
}
$source = Join-Path $deliveryRoot 'source'
foreach ($name in $sourceNames) { Copy-Item -LiteralPath (Join-Path $sourceRoot $name) -Destination (Join-Path $source $name) }
$savedEnvironment = @{}
foreach ($key in @('PYINSTALLER_CONFIG_DIR','TMP','TEMP','PYTHONDONTWRITEBYTECODE','SC_RECEIVER_TIMING')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
try {
    $env:PYINSTALLER_CONFIG_DIR = Join-Path $deliveryRoot 'cache'
    $env:TMP = Join-Path $deliveryRoot 'temp'
    $env:TEMP = $env:TMP
    $env:PYTHONDONTWRITEBYTECODE = '1'
    $env:SC_RECEIVER_TIMING = '0'
    $outputName = 'Simple Controller Receiver (Camera Follow Controls and Game Mouse)'
    $arguments = @('-B','-m','PyInstaller','--onefile','--console','--noupx',
        '--name',$outputName,'--distpath',(Join-Path $deliveryRoot 'dist'),
        '--workpath',(Join-Path $deliveryRoot 'build'),'--specpath',(Join-Path $deliveryRoot 'spec'),
        '--paths',$source,'--hidden-import','relative_mouse','--hidden-import','face_input_server',
        '--hidden-import','simple_controller_receiver','--hidden-import','receiver_timing','--hidden-import','desktop_output',
        '--hidden-import','voice_input_server','--hidden-import','camera_follow','--hidden-import','phone_output_protocol',
        '--add-binary',((Join-Path $dllRoot 'x64\ViGEmClient.dll') + ';vgamepad\win\vigem\client\x64'),
        '--add-binary',((Join-Path $dllRoot 'x86\ViGEmClient.dll') + ';vgamepad\win\vigem\client\x86'),
        (Join-Path $source 'receiver_diagnostics.py'))
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed: $LASTEXITCODE. New output retained at $deliveryRoot" }
    $exe = Join-Path $deliveryRoot ('dist\' + $outputName + '.exe')
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Expected EXE missing.' }
    $inputs = foreach ($name in $sourceNames) {
        $path = Join-Path $source $name
        @{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    $inputs += foreach ($architecture in @('x64','x86')) {
        $path = Join-Path $dllRoot "$architecture\ViGEmClient.dll"
        @{path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
    }
    $packages = & $Python -B -c "import importlib.metadata as m,json;print(json.dumps({n:m.version(n) for n in ['pyinstaller','vgamepad','keyboard','mouse','pyautogui']}))"
    if ($LASTEXITCODE -ne 0) { throw 'Could not record build package versions.' }
    @{
        built_at_utc=[DateTime]::UtcNow.ToString('o');python=$Python;packages=($packages | ConvertFrom-Json)
        inputs=@($inputs);executable=@{path=$exe;sha256=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash}
        baseline_simplecontroller_commit='10512b755df37613d6d5957da07d7a8280122ea3'
        baseline_facial_commit='06df36643f97350a41b19c4929e7d20825a7b314'
        baseline_exe_sha256='23D8121A1D2044DA470A8861FCF7C2C124F43FEE23F12CCF8CFE8DE2D3E1449C'
        validation='Packaged only. EXE not launched. Native/game validation pending.'
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $deliveryRoot 'build-report.json') -Encoding utf8
    Write-Output "New EXE: $exe"
    Write-Output 'Old receivers and shortcuts are preserved. Run one receiver only.'
} finally {
    foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process') }
}
