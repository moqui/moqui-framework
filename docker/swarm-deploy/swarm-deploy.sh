#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 5 ]; then
    echo "Usage: $0 <node1_ip> <node2_ip> <node3_ip> <ssh_user> <ssh_password>"
    echo "Example: ./swarm-deploy.sh 192.168.1.10 192.168.1.11 192.168.1.12 admin admin"
    exit 1
fi

node1_ip="$1"
node2_ip="$2"
node3_ip="$3"
SSH_USER="$4"
export SSHPASS="$5" # Export the password for sshpass (option -e)

# Setup SSH connection strings
node1="${SSH_USER}@${node1_ip}"
node2="${SSH_USER}@${node2_ip}"
node3="${SSH_USER}@${node3_ip}"
GATEWAY_DEVICE_ID="${GATEWAY_DEVICE_ID:-GW_EDGE_01}"

# Helpers
run_ssh() {
    # $1 = target (user@ip), $2 = command
    sshpass -e ssh -o StrictHostKeyChecking=no -o ConnectTimeout=10 "$1" "$2"
}

run_scp() {
    # $1 = source, $2 = dest
    sshpass -e scp -o StrictHostKeyChecking=no -r $1 $2
}

node1_name=$(run_ssh ${node1} "hostname")
node2_name=$(run_ssh ${node2} "hostname")
node3_name=$(run_ssh ${node3} "hostname")

echo "Detected hostnames: $node1_name"
echo "Target nodes"
echo "Nodo 1 (${node1_ip}): ${node1_name}"
echo "Nodo 2 (${node2_ip}): ${node2_name}"
echo "Nodo 3 (${node3_ip}): ${node3_name}"

echo "Installing Docker on nodes"
#sshpass -p${SSH_PASS} ssh${node1} "/bin/bash -s" < install-docker.sh
#sshpass -p${SSH_PASS} ssh${node2} "/bin/bash -s" < install-docker.sh
#sshpass -p${SSH_PASS} ssh${node3} "/bin/bash -s" < install-docker.sh
# echo "SSH_PASS nodes"
# sshpass -p${SSH_PASS} ssh${node1} "sudo reboot"
# sshpass -p${SSH_PASS} ssh${node2} "sudo reboot"
# sshpass -p${SSH_PASS} ssh${node3} "sudo reboot"
# sleep 60

echo "Transferring deployment files to node1"
run_ssh ${node1} "mkdir -p ~/deploy/nginx/certs ~/deploy/yugabyte ~/deploy/activemq ~/deploy/device-gateway"
run_scp "*.yml" "${node1}:~/deploy/"
run_scp "yugabyte" "${node1}:~/deploy/"
run_scp "grafana" "${node1}:~/deploy/"
run_scp "activemq/artemis-start.sh" "${node1}:~/deploy/activemq/"
run_scp "activemq/broker.xml" "${node1}:~/deploy/activemq/"
run_scp "nginx/*.conf" "${node1}:~/deploy/nginx/"
run_scp "nginx/certs/*.pem" "${node1}:~/deploy/nginx/certs/"
run_scp "device-gateway/gateway-mqtt.properties" "${node1}:~/deploy/device-gateway/"

# Example output:
#     Swarm initialized: current node (aowkrt21qx3tk7ordgdpqf1h7) is now a manager.
#     To add a worker to this swarm, run the following command:
#     docker swarm join --token SWMTKN-1-1wol43u7cj4i214hlxbaairbotj6cgtfazp8dqrwyxi3vicucb-ary5bc2y88m9k8800ctpv6l4x 10.96.79.158:2377
#     To add a manager to this swarm, run 'docker swarm join-token manager' and follow the instructions.
echo "Initializing Swarm"
run_ssh ${node1} "docker swarm init --advertise-addr ${node1_ip}"
# Retrieve and store the join token for managers
TOKEN_MANAGER=$(run_ssh ${node1} "docker swarm join-token manager -q")
# Retrieve and store the join token for workers
TOKEN_WORKER=$(run_ssh ${node1} "docker swarm join-token worker -q")

echo "Joining nodes"
run_ssh ${node2} "docker swarm join --token ${TOKEN_MANAGER} ${node1_ip}:2377"
run_ssh ${node3} "docker swarm join --token ${TOKEN_MANAGER} ${node1_ip}:2377"
# sshpass -p${SSH_PASS} ssh${node2} "docker swarm join --token ${TOKEN_WORKER} ${node1_ip}:2377"
# sshpass -p${SSH_PASS} ssh${node3} "docker swarm join --token ${TOKEN_WORKER} ${node1_ip}:2377"

echo "Swarm sanity check"
run_ssh ${node1} "docker node ls"

echo "Applying labels to nodes"
run_ssh ${node1} "docker node update \
    --label-add edge_node=true \
    --label-add db_node1=true \
    --label-add search_node1=true \
    --label-add search_dashboards=true \
    ${node1_name}"

run_ssh ${node1} "docker node update \
    --label-add edge_node=true \
    --label-add broker_node1=true \
    --label-add db_node2=true \
    --label-add search_node2=true \
    --label-add grafana_node=true \
    --label-add gateway_node=true \
    ${node2_name}"

run_ssh ${node1} "docker node update \
    --label-add edge_node=true \
    --label-add broker_node2=true \
    --label-add db_node3=true \
    --label-add search_node3=true \
    --label-add gateway_node=true \
    ${node3_name}"

echo "Creating bind mount directories on each node"
for node in ${node1} ${node2} ${node3}; do
    run_ssh ${node} "sudo mkdir -p /data/yugabyte/data"
    run_ssh ${node} "sudo mkdir -p /data/opensearch/data /data/opensearch/snapshots"
    run_ssh ${node} "sudo chown -R 1000:1000 /data/opensearch"
done

echo "Creating external docker volumes"
run_ssh ${node1} "docker volume create moqui-gateway-data"
run_ssh ${node1} "docker volume create moqui-gateway-logs"

echo "Creating overlay networks"
run_ssh ${node1} "docker network create --driver overlay --attachable dbnet"
run_ssh ${node1} "docker network create --driver overlay --attachable searchnet"
run_ssh ${node1} "docker network create --driver overlay --attachable msgnet"
run_ssh ${node1} "docker network create --driver overlay --attachable servernet"

echo "Creating configs and secrets"
run_ssh ${node1} "cd ~/deploy && \
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
echo "Using GATEWAY_DEVICE_ID=${GATEWAY_DEVICE_ID} for moqui-device-gateway"
run_ssh ${node1} "cd ~/deploy && docker stack deploy -c yugabyte-stack.yml moqui-db"
sleep 10
run_ssh ${node1} "cd ~/deploy && docker stack deploy -c opensearch-stack.yml moqui-search"
sleep 10
run_ssh ${node1} "cd ~/deploy && docker stack deploy -c activemq-stack.yml moqui-broker"
sleep 10
run_ssh ${node1} "cd ~/deploy && docker stack deploy -c moqui-stack.yml moqui"
run_ssh ${node1} "cd ~/deploy && docker stack deploy -c nginx-stack.yml moqui-edge"
run_ssh ${node1} "cd ~/deploy && export GATEWAY_DEVICE_ID='${GATEWAY_DEVICE_ID}' && docker stack deploy -c device-gateway-stack.yml moqui-gateway"

# Check services
run_ssh ${node1} "docker stack services moqui-db"
run_ssh ${node1} "docker stack services moqui-search"
run_ssh ${node1} "docker stack services moqui-broker"
run_ssh ${node1} "docker stack services moqui"
run_ssh ${node1} "docker stack services moqui-edge"
run_ssh ${node1} "docker stack services moqui-gateway"

echo "Deployment complete"
