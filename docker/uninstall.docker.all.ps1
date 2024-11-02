REM stop all containers
docker stop $(docker ps -aq)

REM remove containers
docker rm $(docker ps -aq)

REM remove images
docker image rm $(docker images -aq)