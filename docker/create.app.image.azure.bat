REM docker stop moqui-app
REM docker rm moqui-app
REM #docker rmi $(docker images |grep 'moqui'

REM "Build moqui-plus-runtime.war"
cd ..
gradlew addRuntime -x test

cd docker/simple
docker-build.bat ../.. rskdev.azurecr.io/moqui/moqui_issk_app
