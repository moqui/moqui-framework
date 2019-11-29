SET image_name=%~1

REM docker stop moqui-app
REM docker rm moqui-app
REM #docker rmi $(docker images |grep 'moqui'

REM "Build moqui-plus-runtime.war"
cd ..
call gradlew addRuntime -x test

cd docker\simple
docker-build.bat ../.. %image_name%