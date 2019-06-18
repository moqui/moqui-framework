#! /bin/bash

# move file from upload location
mv /tmp/war_upload/moqui-plus-runtime.war /opt/moqui_deploy/issk-mis

# stop container, remove container and rebuild image
docker stop moqui-app
docker rm moqui-app
docker rmi $(docker images |grep 'moqui')
cd simple
./docker-build.sh ../..

# run docker
docker run -p 127.0.0.1:8080:80 --network moqui_default  --name moqui-app moqui

# INCORRECT
# incorrect port setup
# docker run -p 8080:8080 --network moqui_default  --name moqui-app moqui
