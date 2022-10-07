#! /bin/bash

search_name=opensearch
if [ -d runtime/opensearch/bin ]; then search_name=opensearch;
elif [ -d runtime/elasticsearch/bin ]; then search_name=elasticsearch;
fi

rm -Rf runtime/
rm -Rf runtime1/
rm -Rf runtime2/
rm -Rf db/
rm -Rf $search_name/data/nodes
rm $search_name/logs/*.log

docker rm moqui-server
docker rm moqui-database
docker rm nginx-proxy
