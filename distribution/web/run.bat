@echo off
REM Infinia portable Web launcher. Requires Java 21 on PATH (or JAVA_HOME).
REM The backend binds loopback only (127.0.0.1); pass extra HeadlessLauncher args through.
setlocal enabledelayedexpansion
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
REM Parse the major version from the first line of `java -version`. Java 9+ prints it as
REM `... version "<major>.<minor>"...`; Java 8 used `1.8.0`. We extract the first integer
REM and require it to be at least 21, matching run.sh's check.
set "MAJOR="
for /f "tokens=2 delims=." %%V in ('"%JAVA%" -version 2^>^&1') do (
  REM %%V starts with ' version "NN' (Java 9+) or ' version "1' (Java 8).
  set "TOKEN=%%V"
  REM Strip leading ' version "' and surrounding quotes/whitespace.
  for /f "tokens=* delims= " %%C in ("!TOKEN!") do set "TOKEN=%%C"
  set "TOKEN=!TOKEN:version=!"
  set "TOKEN=!TOKEN:"=!"
  set "TOKEN=!TOKEN: =!"
  if not "!TOKEN!"=="" (
    set "MAJOR=!TOKEN!"
    goto :gotmajor
  )
)
:gotmajor
if "%MAJOR%"=="" (
  echo Could not determine Java version from %JAVA%; Java 21 is required 1>&2
  exit /b 1
)
if %MAJOR% LSS 21 (
  echo Java 21 is required, found version %MAJOR% 1>&2
  exit /b 1
)
REM 若用户未显式传 --token=<t>,生成随机 token 避免默认认证关闭。
REM 精确校验参数；空值、重复值和 --tokenized 等拼写错误均 fail closed。
set "HAS_TOKEN=0"
set "TOKEN_ERROR="
for %%A in (%*) do (
  set "ARG=%%~A"
  if "!ARG:~0,8!"=="--token=" (
    set "TOKEN_VALUE=!ARG:~8!"
    set "TOKEN_NONSPACE=!TOKEN_VALUE: =!"
    if not defined TOKEN_NONSPACE set "TOKEN_ERROR=Invalid --token argument: use --token=^<non-empty value^>"
    if "!HAS_TOKEN!"=="1" set "TOKEN_ERROR=Invalid arguments: --token may be supplied only once"
    set "HAS_TOKEN=1"
  ) else if "!ARG:~0,7!"=="--token" (
    set "TOKEN_ERROR=Invalid token argument '!ARG!': use --token=^<non-empty value^>"
  )
)
if defined TOKEN_ERROR (
  echo !TOKEN_ERROR! 1>&2
  exit /b 2
)
if "!HAS_TOKEN!"=="0" (
  set "GEN_TOKEN=zf-%RANDOM%%RANDOM%-%TIME:~6,2%%TIME:~9,2%"
  echo Generated per-launch token (pass --token=^<t^> to override): !GEN_TOKEN! >&2
  "%JAVA%" -Dfengyu.runtime.dir="%ROOT%data" -Dfengyu.plugins.official-directory="%ROOT%plugins" -Dfengyu.update.portable=true -jar "%ROOT%Infinia.jar" --token="!GEN_TOKEN!" %*
) else (
  "%JAVA%" -Dfengyu.runtime.dir="%ROOT%data" -Dfengyu.plugins.official-directory="%ROOT%plugins" -Dfengyu.update.portable=true -jar "%ROOT%Infinia.jar" %*
)
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
