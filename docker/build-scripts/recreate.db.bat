@echo off

REM check important variables, required to run
IF DEFINED _PYTHON_EXEC (ECHO Python executable: %_PYTHON_EXEC%)
IF DEFINED _PGDUMP_EXEC (ECHO PG_DUMP executable: %_PGDUMP_EXEC%)
IF DEFINED _PYTHON_EXEC (SET PYTHON_TO_RUN=%_PYTHON_EXEC%) ELSE (SET PYTHON_TO_RUN=python)
IF DEFINED _PGDUMP_EXEC (SET PGDUMP_TO_RUN=%_PGDUMP_EXEC%) ELSE (SET PGDUMP_TO_RUN=pg_dump)
IF "%PYTHON_TO_RUN%"=="" OR "%PGDUMP_TO_RUN%"=="" ( ECHO Basic variables required to run the script not set (`pg_dump.exe=%PGDUMP_TO_RUN%` and/or `python=%_PYTHON_EXEC%` executable) & goto e)

SET _APP_BUILD=0

SET seed_info="seed,seed-after"
SET db=moqui_bck_db
SET user=moqui
SET pwd=postgres
SET host=localhost
SET port=5431
SET conf_file_name=MoquiDumpConf.xml

call build.app.and.database.bat %seed_info% 0 %db% %conf_file_name%

:e