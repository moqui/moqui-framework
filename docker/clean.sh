#! /bin/bash

rm -Rf runtime/
rm -Rf runtime1/
rm -Rf runtime2/
rm -Rf db/
rm -Rf elasticsearch/data
rm -Rf elasticsearch/logs

docker rm moqui-server
docker rm moqui-database
docker rm nginx-proxy
