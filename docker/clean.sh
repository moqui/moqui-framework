#! /bin/bash

rm -Rf runtime/
rm -Rf db/

docker rm moqui-server
docker rm mysql-moqui
docker rm nginx-proxy
