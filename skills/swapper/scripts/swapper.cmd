@echo off
setlocal EnableExtensions DisableDelayedExpansion
if "%~2"=="" (echo Usage: swapper.cmd [option] [jsonParam] >&2 & exit /b 64)
set "OPTION=%~1"
set "SWAPPER_ARGUMENTS=%~2"
set "TOOL="
set "METHOD=tools/call"
if "%OPTION%"=="--tools" set "METHOD=tools/list"
if "%OPTION%"=="--watch" set "TOOL=watch"
if "%OPTION%"=="--outer-watch" set "TOOL=outer_watch"
if "%OPTION%"=="--trace" set "TOOL=trace"
if "%OPTION%"=="--logs" set "TOOL=read_logs"
if "%OPTION%"=="--decompile" set "TOOL=decompile"
if "%OPTION%"=="--find-subclasses" set "TOOL=find_subclasses"
if "%OPTION%"=="--list" set "TOOL=list_transformers"
if "%OPTION%"=="--eval" set "TOOL=eval"
if "%OPTION%"=="--exec" set "TOOL=exec"
if "%OPTION%"=="--change-body" set "TOOL=change_body"
if "%OPTION%"=="--change-result" set "TOOL=change_result"
if "%OPTION%"=="--replace-class" set "TOOL=replace_class"
if "%OPTION%"=="--delete" set "TOOL=delete_transformer"
if "%OPTION%"=="--reset" set "TOOL=reset"
if "%METHOD%"=="tools/list" goto call
if "%TOOL%"=="" (echo Unknown option: %OPTION% >&2 & exit /b 64)
if "%OPTION%"=="--eval" goto confirm
if "%OPTION%"=="--exec" goto confirm
if "%OPTION%"=="--change-body" goto confirm
if "%OPTION%"=="--change-result" goto confirm
if "%OPTION%"=="--replace-class" goto confirm
goto call
:confirm
if /I "%SWAPPER_CONFIRM%"=="YES" goto call
echo %OPTION% may execute or change code in the target JVM. Obtain user confirmation, then rerun with SWAPPER_CONFIRM=YES. >&2
exit /b 77
:call
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $a=$env:SWAPPER_ARGUMENTS | ConvertFrom-Json; if ($a -isnot [pscustomobject]) { throw 'jsonParam must be a JSON object' }; $id='swapper-'+[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()+'-'+$PID; if ($env:METHOD -eq 'tools/list') { $request=@{jsonrpc='2.0';id=$id;method='tools/list'} } else { $request=@{jsonrpc='2.0';id=$id;method='tools/call';params=@{name=$env:TOOL;arguments=$a}} }; $url=if ($env:SWAPPER_URL) {$env:SWAPPER_URL} else {'http://127.0.0.1:8000/mcp'}; $response=Invoke-RestMethod -Method Post -Uri $url -ContentType 'application/json' -Body ($request | ConvertTo-Json -Depth 20); $response | ConvertTo-Json -Depth 30; if ($response.error -or $response.result.isError) { exit 1 }"
exit /b %ERRORLEVEL%
