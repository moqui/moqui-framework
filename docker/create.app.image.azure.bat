SET image_name=%~1
SET docker_repo=%~2
SET dockerfile=%~3

REM docker stop moqui-app
REM docker rm moqui-app
REM #docker rmi $(docker images |grep 'moqui'

REM "Build moqui-plus-runtime.war"
pushd ..
call gradlew addRuntime -x test

pushd docker\simple
docker-build.bat ../.. %image_name% %docker_repo% %dockerfile%