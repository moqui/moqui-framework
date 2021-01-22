#!/bin/sh
# wait-for-postgres.sh

postgres_host=$1
postgres_port=$2
shift 2
cmd="$@"

echo "Command prepared '$cmd'"

# wait for the postgres docker to be running
while ! nc $postgres_host $postgres_port; do
  >&2 echo "Postgres is unavailable - sleeping"
  sleep 1
done

>&2 echo "Postgres is up - executing command"

# run the command
exec $cmd