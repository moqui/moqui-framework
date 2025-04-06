#!/bin/bash

# Ensure the network is created only if it does not already exist
docker network inspect ars-int >/dev/null 2>&1 || docker network create ars-int

# "Creating database dump for purpose of having a clear and filled database"
# get into the simple-build directory
# shellcheck disable=SC2164
pushd ../gradle-tasks/fill-database

# 1. create database server (inside separate component) and fill it
docker compose -p pg-dump   -f create-database.yml build --no-cache
docker compose -p pg-dump   -f create-database.yml up -d

# store IP of host in order to use it for db import later on
ip4=$(/sbin/ip -o -4 addr list | awk '!/ lo / {print $4; exit}' | cut -d/ -f1)
echo "Connecting to PG_LOAD_SERVER: $ip4"

# 2. run application component (inside separate component as well) - this is the component that is being built
# this will actually initialize the database, without running the component
# docker compose -p moqui-fill -f fill-database.yml build --no-cache --build-arg PG_LOAD_SERVER="$ip4"
docker compose -p moqui-fill -f fill-database.yml build --no-cache --build-arg PG_LOAD_SERVER="dev-postgres"

# run DUMP script
cat ./DumpDatabase.sql | docker exec -i dev-postgres su root

# kill containers
docker compose -p pg-dump -f create-database.yml down --rmi local -v

# return back to original dir
popd
