ECHO OFF
ECHO "preparing moqui environment"

pushd ..

SET COMP_FILE=%~1
SET MOQUI_HOME=%~2

if [%1] == [] (
    ECHO "setting compose file to default"
    SET COMP_FILE="test-nginx-postgres-compose.yml"
)

if [%2] == [] (
    ECHO "setting path to default"
    SET MOQUI_HOME=".."
)

REM if not exist runtime mkdir runtime
REM if not exist runtime\conf xcopy /E /i %MOQUI_HOME%\runtime\conf runtime\conf
REM if not exist runtime\lib xcopy /E /i %MOQUI_HOME%\runtime\lib runtime\lib
REM if not exist runtime\classes xcopy /E /i %MOQUI_HOME%\runtime\classes runtime\classes
REM if not exist runtime\log xcopy /E /i %MOQUI_HOME%\runtime\log runtime\log
REM if not exist runtime\txlog xcopy /E /i %MOQUI_HOME%\runtime\txlog runtime\txlog
REM if not exist runtime\db xcopy /E /i %MOQUI_HOME%\runtime\db runtime\db
REM # if [ ! -e runtime/elasticsearch ]; then cp -R $MOQUI_HOME/runtime/elasticsearch runtime/; fi

REM set the project name to 'moqui', network will be called 'moqui_default'
docker-compose -f %COMP_FILE% -p moqui up -d