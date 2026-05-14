#!/usr/bin/env bash
set -euo pipefail

# Multipass installation (uncomment if needed)
# sudo snap install multipass
# Multipass sanity check 
multipass version
GATEWAY_DEVICE_ID="${GATEWAY_DEVICE_ID:-GW_EDGE_01}"

echo "Launching VMs..."
multipass launch --name node1 --cpus 2 --memory 3G --disk 10G 22.04
multipass launch --name node2 --cpus 2 --memory 3G --disk 10G 22.04
multipass launch --name node3 --cpus 2 --memory 3G --disk 10G 22.04
# Cluster sanity check
multipass list

echo "Installing Docker on nodes"
for node in node1 node2 node3; do
    multipass exec $node -- sudo bash -s < install-docker.sh
done
# Restart VMs so docker group membership takes effect
multipass stop node1 node2 node3
multipass start node1 node2 node3
echo "Waiting for VMs to come back up..."
sleep 20

echo "Transferring deployment files to node1"
multipass exec node1 -- mkdir -p /home/ubuntu/deploy/nginx/certs
multipass exec node1 -- mkdir -p /home/ubuntu/deploy/yugabyte
multipass exec node1 -- mkdir -p /home/ubuntu/deploy/activemq
multipass exec node1 -- mkdir -p /home/ubuntu/deploy/grafana/datasource
multipass exec node1 -- mkdir -p /home/ubuntu/deploy/device-gateway

multipass transfer nginx-stack.yml node1:/home/ubuntu/deploy/
multipass transfer moqui-stack.yml node1:/home/ubuntu/deploy/
multipass transfer opensearch-stack.yml node1:/home/ubuntu/deploy/
multipass transfer activemq-stack.yml node1:/home/ubuntu/deploy/
multipass transfer yugabyte-stack.yml node1:/home/ubuntu/deploy/
multipass transfer device-gateway-stack.yml node1:/home/ubuntu/deploy/
multipass transfer yugabyte/yb-start.sh node1:/home/ubuntu/deploy/yugabyte/
multipass transfer yugabyte/bootstrap.sh node1:/home/ubuntu/deploy/yugabyte/
multipass transfer yugabyte/bootstrap-run.sh node1:/home/ubuntu/deploy/yugabyte/
multipass transfer activemq/artemis-start.sh node1:/home/ubuntu/deploy/activemq/
multipass transfer activemq/broker.xml node1:/home/ubuntu/deploy/activemq/
multipass transfer grafana/datasource/datasource.yml node1:/home/ubuntu/deploy/grafana/datasource/
multipass transfer device-gateway/gateway-mqtt.properties node1:/home/ubuntu/deploy/device-gateway/

multipass transfer nginx/nginx-stack.conf node1:/home/ubuntu/deploy/nginx/
multipass transfer nginx/moqui-stack.conf node1:/home/ubuntu/deploy/nginx/
multipass transfer nginx/moqui-proxy-stack.conf node1:/home/ubuntu/deploy/nginx/
multipass transfer nginx/certs/fullchain.pem node1:/home/ubuntu/deploy/nginx/certs/
multipass transfer nginx/certs/privkey.pem node1:/home/ubuntu/deploy/nginx/certs/

# Example output:
#     Swarm initialized: current node (aowkrt21qx3tk7ordgdpqf1h7) is now a manager.
#     To add a worker to this swarm, run the following command:
#     docker swarm join --token SWMTKN-1-1wol43u7cj4i214hlxbaairbotj6cgtfazp8dqrwyxi3vicucb-ary5bc2y88m9k8800ctpv6l4x 10.96.79.158:2377
#     To add a manager to this swarm, run 'docker swarm join-token manager' and follow the instructions.
echo "Initializing Swarm"
node1_ip=$(multipass info node1 --format json | jq -r '.info["node1"].ipv4[0]')
multipass exec node1 -- docker swarm init --advertise-addr $node1_ip
# Retrieve and store the join token for managers
TOKEN_MANAGER=$(multipass exec node1 -- docker swarm join-token manager -q)
# Retrieve and store the join token for workers
TOKEN_WORKER=$(multipass exec node1 -- docker swarm join-token worker -q)
echo "Joining nodes"
multipass exec node2 -- docker swarm join --token $TOKEN_MANAGER $node1_ip:2377
multipass exec node3 -- docker swarm join --token $TOKEN_MANAGER $node1_ip:2377
# multipass exec node2 -- docker swarm join --token $TOKEN_WORKER $node1_ip:2377
# multipass exec node3 -- docker swarm join --token $TOKEN_WORKER $node1_ip:2377
echo "Swarm sanity check"
multipass exec node1 -- docker node ls

echo "Applying labels to nodes"
# Apply labels to nodes
multipass exec node1 -- docker node update \
    --label-add edge_node=true \
    --label-add db_node1=true \
    --label-add search_node1=true \
    --label-add search_dashboards=true \
    node1
multipass exec node1 -- docker node update \
    --label-add edge_node=true \
    --label-add broker_node1=true \
    --label-add db_node2=true \
    --label-add search_node2=true \
    --label-add grafana_node=true \
    --label-add gateway_node=true \
    node2
multipass exec node1 -- docker node update \
    --label-add edge_node=true \
    --label-add broker_node2=true \
    --label-add db_node3=true \
    --label-add search_node3=true \
    --label-add gateway_node=true \
    node3

echo "Creating bind mount directories on each node"
for node in node1 node2 node3; do
    multipass exec $node -- sudo mkdir -p /data/yugabyte/data
    multipass exec $node -- sudo mkdir -p /data/opensearch/data /data/opensearch/snapshots
    multipass exec $node -- sudo chown -R 1000:1000 /data/opensearch
done

echo "Creating external docker volumes"
multipass exec node1 -- docker volume create moqui-gateway-data
multipass exec node1 -- docker volume create moqui-gateway-logs

echo "Creating overlay networks"
multipass exec node1 -- docker network create --driver overlay --attachable dbnet
multipass exec node1 -- docker network create --driver overlay --attachable searchnet
multipass exec node1 -- docker network create --driver overlay --attachable msgnet
multipass exec node1 -- docker network create --driver overlay --attachable servernet

echo "Creating configs and secrets"
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && \
    docker config create nginx.conf ./nginx/nginx-stack.conf && \
    docker config create moqui.conf ./nginx/moqui-stack.conf && \
    docker config create moqui-proxy.conf ./nginx/moqui-proxy-stack.conf && \
    docker config create yb_start_node ./yugabyte/yb-start.sh && \
    docker config create bootstrap_run ./yugabyte/bootstrap-run.sh && \
    docker config create bootstrap ./yugabyte/bootstrap.sh && \
    docker config create grafana_datasource ./grafana/datasource/datasource.yml && \
    docker config create artemis_start ./activemq/artemis-start.sh && \
    docker config create artemis_xml ./activemq/broker.xml && \
    docker secret create moqui-cert ./nginx/certs/fullchain.pem && \
    docker secret create moqui-key ./nginx/certs/privkey.pem && \
    printf 'yugabyte' | docker secret create yb_superuser - && \
    printf 'yugabyte' | docker secret create yb_superdb - && \
    printf 'moqui' | docker secret create moqui_user - && \
    printf 'moqui' | docker secret create moqui_password - && \
    printf 'moqui' | docker secret create moqui_db_user - && \
    printf 'moqui' | docker secret create moqui_db_password - && \
    printf 'moqui' | docker secret create moqui_crypt_password - && \
    printf 'moqui' | docker secret create moqui_search_password - && \
    printf 'test-pass' | docker secret create moqui_hazelcast_password - && \
    printf 'admin' | docker secret create moqui_search_user - && \
    printf 'admin_super_secret' | docker secret create grafana_admin_password - && \
    printf 'artemis' | docker secret create artemis_user - && \
    printf 'artemis' | docker secret create artemis_password - && \
    printf 'cluster' | docker secret create artemis_cluster_user - && \
    printf 'cluster' | docker secret create artemis_cluster_password - && \
    printf 'change-me' | docker secret create gateway_api_token - && \
    docker config create device_gateway_config ./device-gateway/gateway-mqtt.properties"

echo "Deploying Swarm stacks"
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && docker stack deploy -c yugabyte-stack.yml moqui-db"
echo "Waiting for DB..."
sleep 10
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && docker stack deploy -c opensearch-stack.yml moqui-search"
echo "Waiting for Search..."
sleep 10
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && docker stack deploy -c activemq-stack.yml moqui-broker"
echo "Waiting for Broker..."
sleep 10
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && docker stack deploy -c moqui-stack.yml moqui"
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && docker stack deploy -c nginx-stack.yml moqui-edge"
echo "Using GATEWAY_DEVICE_ID=${GATEWAY_DEVICE_ID} for moqui-device-gateway"
multipass exec node1 -- bash -c "cd /home/ubuntu/deploy && export GATEWAY_DEVICE_ID='${GATEWAY_DEVICE_ID}' && docker stack deploy -c device-gateway-stack.yml moqui-gateway"

# Check services
multipass exec node1 -- docker stack services moqui-db
multipass exec node1 -- docker stack services moqui-search
multipass exec node1 -- docker stack services moqui-broker
multipass exec node1 -- docker stack services moqui
multipass exec node1 -- docker stack services moqui-edge
multipass exec node1 -- docker stack services moqui-gateway

echo "Deployment complete."

# Cleaning
# Stop VMs
# multipass stop node1 node2 node3
# Remove VMs
# multipass delete node1 node2 node3
# multipass purge
