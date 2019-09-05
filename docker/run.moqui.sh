#! /bin/bash

#docker stop moqui-app
#docker rm moqui-app
#docker rmi $(docker images |grep 'moqui')
cd simple
./docker-build.sh ../..
docker run -p 127.0.0.1:8080:80 --network moqui_default  --name moqui-app moqui

# incorrect port setup
# docker run -p 8080:8080 --network moqui_default  --name moqui-app moqui
