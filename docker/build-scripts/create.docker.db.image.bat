@echo off

REM check important variables, required to run
IF DEFINED _PYTHON_EXEC (ECHO Python executable: %_PYTHON_EXEC%)
IF DEFINED _PGDUMP_EXEC (ECHO PG_DUMP executable: %_PGDUMP_EXEC%)
IF DEFINED _PYTHON_EXEC (SET PYTHON_TO_RUN=%_PYTHON_EXEC%) ELSE (SET PYTHON_TO_RUN=python)
IF DEFINED _PGDUMP_EXEC (SET PGDUMP_TO_RUN=%_PGDUMP_EXEC%) ELSE (SET PGDUMP_TO_RUN=pg_dump)
IF "%PYTHON_TO_RUN%"=="" OR "%PGDUMP_TO_RUN%"=="" ( ECHO Basic variables required to run the script not set (`pg_dump.exe=%PGDUMP_TO_RUN%` and/or `python=%_PYTHON_EXEC%` executable) & goto e)

SET _APP_BUILD=0

REM pushd %cd%
SET docker_dir=%cd%

SET seed_info="seed,seed-after"
SET start_container=0
SET db=moqui_bck_db
SET user=moqui
SET pwd=postgres
SET host=localhost
SET port=5431
SET conf_file_name=MoquiDumpConf.xml

ECHO Generate Docker config file '%conf_file_name%'
%PYTHON_TO_RUN% config_generator.py ^
    --gen_switch=1 ^
    --db=%db% ^
    --db_user=%user% ^
    --db_pwd=%pwd% ^
    --db_host=%host% ^
    --db_port=%port% ^
    --config_file_name=%conf_file_name%

call build.app.and.database.bat %seed_info% %start_container% %db% %conf_file_name%

if %ERRORLEVEL%==0 (
    ECHO Cannot proceed with DB dump
    goto e
)

ECHO Performing database dump
%PGDUMP_TO_RUN% --no-owner -U moqui -p 5431 %db% > ..\db\%db%_dump.sql

REM create docker image of database
pushd %docker_dir%

ECHO Building DB Docker image
call docker.image.build.bat %_IMAGE_NAME% %_REPO_NAME% %_IMAGE_VERSION% %_DOCKERFILE%

ECHO Erase DB dumps '%db_dump_path%'
pushd %db_dump_path%
REM del /s /q *.sql

:e