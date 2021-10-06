@echo off

REM check important variables, required to run
IF DEFINED _MOQUI_CONF_FILE (SET moqui_conf_file=%_MOQUI_CONF_FILE%) ELSE (SET moqui_conf_file=conf\MoquiDevConf.xml)

ECHO    Build moqui-plus-runtime.war with 'conf=%moqui_conf_file%'

pushd ..\..
call gradlew addRuntime -Dmoqui.conf=%moqui_conf_file% addRuntime -x test
popd


ECHO    Building DB Docker image
call docker.image.build.bat %_IMAGE_NAME% %_REPO_NAME% %_IMAGE_VERSION% %_DOCKERFILE%
