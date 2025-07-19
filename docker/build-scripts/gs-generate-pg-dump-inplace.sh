#!/bin/bash

# Check if port 5432 is already in use
if ss -tuln | grep ':5432'; then
    echo "Error: Port 5432 is already in use"
    exit 1
fi

if [ ! -d "$PWD/../db/dump" ]; then
  echo "Unable to find dump directory"
fi

# create postgres database
docker run -d \
  --name dev-postgres \
  -v $PWD/../db/dump:/tmp \
  -e POSTGRES_USER=moqui \
  -e POSTGRES_DB=moqui \
  -e POSTGRES_PASSWORD=postgres \
  -e PGPORT=5432 \
  -e POSTGRES_HOST_AUTH_METHOD=trust \
  -p 5432:5432 \
  postgres:14.1-alpine

# move to moqui-backend directory
pushd ../.. || exit 1

# display info that may be needed to get things off the ground
echo "If the BUILD is failing, the reason behind it may be, the process has not sufficient rights to execute changes while running build"
echo "Consider running 'chown -R user:user ../../build-blocks/moqui-backend'"
echo "Executing in: $(pwd)"

# THIS PROCEDURES WILL RUN WITH MOQUI-CUSTOMER INCLUDED, BE AWARE
./gradlew clean
./gradlew build
./gradlew load -Dmoqui.conf.dev=conf/MoquiLoadConf.xml

# execute dump
cat ./docker/gradle-tasks/fill-database/DumpDatabase.sql | docker exec -i dev-postgres su root

# remove container
docker rm -v -f dev-postgres

popd