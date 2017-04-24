#! /bin/bash

rm -Rf runtime/
rm -Rf db/

docker rm moqui-server
docker rm moqui-database
docker rm nginx-proxy
