ECHO OFF
ECHO "composing Moqui environment - no DB and no ELASTIC"

SET COMP_FILE=%~1
SET MOQUI_HOME=%~2

if [%1] == [] (
    ECHO "setting compose-file to default"
    SET COMP_FILE="moqui-pg-jdk-11-compose.yml"
)

if [%2] == [] (
    ECHO "setting path to default"
    SET MOQUI_HOME=".."
)

REM get into the simple-build directory
pushd ..\simple

REM unzip built application
7z x ..\%MOQUI_HOME%\moqui-plus-runtime.war

REM set the project name to 'moqui', network will be called 'moqui_default'
docker-compose -f ../%COMP_FILE% -p moqui-dynamic --verbose up -d --build

REM delete all that remains
rmdir /Q /S META-INF
rmdir /Q /S WEB-INF
rmdir /Q /S execlib
rmdir /Q /S runtime
del *.class
del Procfile

REM return back to original dir
popd

pause