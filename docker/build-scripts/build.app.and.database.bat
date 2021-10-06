@echo off

ECHO Building application and database script

SET seed_info=%1
SET start_container=%2
SET db=%3
SET moqui_conf=%4

IF "%~3"=="" (SET db=moqui)

IF "%moqui_conf%"=="" (
    ECHO   Moqui Configuration file not set ...
    exit /b 0 
)

ECHO    Running DB scripts in '%cd%'
REM use sample config file to create databases (test one + enumerator)
REM must also create (manually) non-moqui objects
psql -U postgres -p 5431 -f sql/create.db.sql -v db=%db%

REM set path and change directory
pushd ..\..

REM populate date (using entity-load)
call gradlew -Dmoqui.conf.dev=conf\generated\%moqui_conf% -x test load -Ptypes=%seed_info%
call gradlew -Dmoqui.conf.dev=conf\generated\%moqui_conf% addRuntime -x test

REM return back
popd

exit /b 1