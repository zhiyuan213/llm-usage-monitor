param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$buildRoot = Join-Path $repoRoot '.local/model-tester-build'
& $Python -m venv $buildRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not create Python build environment' }
$buildPython = Join-Path $buildRoot 'Scripts/python.exe'
& $buildPython -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt') 'pyinstaller==6.22.3'
if ($LASTEXITCODE -ne 0) { throw 'Build dependency installation failed' }
& $buildPython -m unittest discover -s $PSScriptRoot -v
if ($LASTEXITCODE -ne 0) { throw 'Desktop contract tests failed' }
$sslDirectory = & $buildPython -c 'import pathlib,ssl; print(pathlib.Path(ssl.__file__).parent)'
if ($LASTEXITCODE -ne 0) { throw 'Could not locate SSL runtime resources' }
$buildArguments = @('--noconfirm', '--onefile', '--windowed', '--name', 'MeterTrailTester',
    '--distpath', (Join-Path $repoRoot '.local/model-tester-fixed'),
    '--workpath', (Join-Path $repoRoot '.local/model-tester-fixed-build'),
    '--specpath', (Join-Path $repoRoot '.local/model-tester-fixed-build'))
# Some Python distributions ship supplemental TLS certificate files alongside
# ssl.py. Keep these resources with the frozen module rather than dropping them.
foreach ($certificate in (Get-ChildItem -LiteralPath $sslDirectory -Filter '*.pem' -File)) {
    $buildArguments += @('--add-data', "$($certificate.FullName);.")
}
$buildArguments += (Join-Path $PSScriptRoot 'app.py')
& $buildPython -m PyInstaller @buildArguments
if ($LASTEXITCODE -ne 0) { throw 'Executable packaging failed' }
Write-Output (Join-Path $repoRoot '.local/model-tester-fixed/MeterTrailTester.exe')
