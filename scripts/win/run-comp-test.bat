@echo off

REM Define the list of repositories and their branches
set "repos=git@github.com:rovnanik-sk/moqui-closure-api.git#rsk-dev,git@github.com:rovnanik-sk/moqui-acc-extractor.git#rsk-dev,git@github.com:rovnanik-sk/moqui-projects-api.git#rsk-dev"

REM Set JAVA_HOME to JAVA 11
call config.bat

REM move one directory up
pushd ..

REM Remove everything from the runtime/component directory except README file
echo Deleting content from runtime/component except README...
pushd runtime\component
for /d %%D in (*) do (
    if /I "%%D" neq "README" rmdir "%%D" /s /q
)
for %%F in (*) do (
    if /I "%%F" neq "README" del "%%F" /q
)
popd

REM Clone the repositories
echo Cloning repositories...
for %%R in (%repos%) do (
    for /f "tokens=1,2 delims=#" %%a in ("%%R") do (
        git clone %%a runtime\component\%%~na -b %%b
        if %errorlevel% neq 0 (
            echo "Error: git clone failed for %%a"
            popd
            exit /b %errorlevel%
        )
    )
)

REM Clean database
call gradlew cleanDb
if %errorlevel% neq 0 (
    echo "Error: gradlew cleanDb failed"
    popd
    exit /b %errorlevel%
)

REM Load
call gradlew load
if %errorlevel% neq 0 (
    echo "Error: gradlew load failed"
    popd
    exit /b %errorlevel%
)

REM MoquiSuite
call gradlew compTest -Pmoqui.log.directory=log/comp-test
if %errorlevel% neq 0 (
    echo "Error: gradlew compTest failed"
    popd
    exit /b %errorlevel%
)

echo "All tasks executed successfully"
popd
pause