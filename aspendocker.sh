#! /usr/bin/env bash
gradle cleanAll gitPullAll
gradle addRuntime
cd docker/simple
./docker-build.sh ../.. sssonline/aspen
docker tag sssonline/aspen sssonline/aspen:`date +%Y-%m-%d`
docker push sssonline/aspen
ssh moqui@207.183.240.72
