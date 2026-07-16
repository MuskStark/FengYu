@echo off
REM Infinia portable Web launcher. Requires Java 21 on PATH (or JAVA_HOME).
REM The backend binds loopback only (127.0.0.1); pass extra HeadlessLauncher args through.
setlocal
set "ROOT=%~dp0"
if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA=java.exe"
)
where "%JAVA%" >nul 2>&1
if errorlevel 1 (
  echo Java 21 is required 1>&2
  exit /b 1
)
"%JAVA%" -Dfengyu.plugins.official-directory="%ROOT%plugins" -jar "%ROOT%Infinia.jar" %*
endlocal
