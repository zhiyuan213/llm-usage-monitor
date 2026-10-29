@echo off
cd /d "%~dp0"
if exist MeterTrailTester.exe (
  start "" "%~dp0MeterTrailTester.exe"
  exit /b
)
where py >nul 2>nul
if not errorlevel 1 (
  py -3 app.py
  if errorlevel 1 pause
  exit /b
)
where python >nul 2>nul
if not errorlevel 1 (
  python app.py
  if errorlevel 1 pause
  exit /b
)
echo Python was not found. Please use the standalone MeterTrailTester.exe package.
pause
