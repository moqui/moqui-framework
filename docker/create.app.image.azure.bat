SET image_name=%~1
SET docker_repo=%~2
SET moqui_conf_file=%~3
SET image_version=%~4

REM docker stop moqui-app
REM docker rm moqui-app
REM #docker rmi $(docker images |grep 'moqui'

ECHO    Build moqui-plus-runtime.war with 'conf=%moqui_conf%'
pushd ..
call gradlew addRuntime -Dmoqui.conf=%moqui_conf% addRuntime -x test


ECHO    Building Docker image '%image_name%' into '%docker_repo%'
pushd docker\simple
docker-build.bat ../.. %image_name% %docker_repo% %image_version%