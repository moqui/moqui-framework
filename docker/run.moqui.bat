REM docker stop moqui-app
REM docker rm moqui-app
REM #docker rmi $(docker images |grep 'moqui')
cd simple
docker-build.bat ../.. moqui
docker run -p 127.0.0.1:8080:80 --network moqui_default  --name moqui-app moqui