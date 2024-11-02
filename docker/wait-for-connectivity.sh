#!/bin/sh
# wait-for-connectivity.sh

req_host=$1
req_port=$2
shift 2
cmd="$@"

echo "Command prepared '$cmd'"

# wait for the postgres docker to be running
while ! nc $req_host $req_port; do
  >&2 echo "Host is unavailable - sleeping"
  sleep 1
done

>&2 echo "Host is up - executing command"

# run the command
exec $cmd