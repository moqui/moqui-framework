#!/bin/bash

# Set options to exit on errors
set -e

# Function to handle errors gracefully
handle_error() {
    echo "Error: $1 failed"
    popd
    popd
    popd
    exit 1
}

# load sharepoint AUTH
JSON_FILE="../../../utils/fast-api-calc/calc/auth/ms_office365_auth.json"

# Check if the JSON file exists
if [ ! -f "$JSON_FILE" ]; then
  echo "Error: JSON file '$JSON_FILE' not found!" >&2
  exit 1
fi

# Read JSON keys into variables using jq
tenantId=$(jq -r '.tenantId' "$JSON_FILE")
clientId=$(jq -r '.clientId' "$JSON_FILE")
clientSecret=$(jq -r '.clientSecret' "$JSON_FILE")

export RS_MS_TENANT_ID="$tenantId"
export RS_MS_CLIENT_ID="$clientId"
export RS_MS_CLIENT_SECRET="$clientSecret"

# Function to check the status of the pg-tester container
check_pg_tester_container() {
    # Check if the container exists
    if [[ $(docker ps -a --format "{{.Names}}" | grep -w "pg-tester") ]]; then
        # Check if the container is running
        if [[ $(docker ps --format "{{.Names}}" | grep -w "pg-tester") ]]; then
            echo "pg-tester container is already running"
        else
            echo "pg-tester container exists but is not running. Starting it..."
            docker start pg-tester
        fi
    else
        echo "pg-tester container does not exist. Creating and starting it..."
        docker run -d --name pg-tester -p 5432:5432 \
            -e POSTGRES_DB=moqui \
            -e POSTGRES_DB_SCHEMA=public \
            -e POSTGRES_USER=postgres \
            -e POSTGRES_PASSWORD=postgres \
            postgres:12.1 -c max_prepared_transactions=10

        echo "Waiting for container to be ready..."
        for i in {1..10}; do
          if docker inspect -f '{{.State.Running}}' pg-tester | grep true; then
           echo "Container is up and running!"
           sleep 7
           break
          fi
          echo "Container is not ready yet. Retrying in 10 seconds..."
          sleep 2
        done

        if ! docker inspect -f '{{.State.Running}}' pg-tester | grep true; then
            echo "Error: Container did not start after 10 retries." >&2
            exit 1
        fi

        echo "Seed database"
        docker exec pg-tester psql -U postgres -c "CREATE DATABASE closure_db;"
        cd build-blocks/moqui-backend
        ./gradlew load -Dmoqui.conf.dev=component/moqui-closure-api/src/test-integration/resources/conf/MoquiIntegrationTestConf.xml
    fi
}

# Move few directories up
pushd ../../../..
mkdir -p automatic-fixes
pushd automatic-fixes

echo "Running local automated integration test"

current_datetime=$(date +"%Y-%m-%d_%H-%M-%S")
rs_dir="$current_datetime-rs"

# clone stack
git clone git@github.com:rovnanik-sk/reporting-stack.git "$rs_dir"

# init customer
pushd $rs_dir
./gradlew initFromScratch

echo "empty env" > customer/.env
echo "insType=deploy" > gradle.properties

# clone moqui and its components
git clone git@github.com:rovnanik-sk/moqui-framework build-blocks/moqui-backend
git clone git@github.com:rovnanik-sk/moqui-runtime build-blocks/moqui-backend/runtime
git clone git@github.com:rovnanik-sk/moqui-closure-api build-blocks/moqui-backend/runtime/component/moqui-closure-api

# check database
check_pg_tester_container

#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 01 - ISSK work-records"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 02 - ISSK project-budget-structure"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 03 - uploading XLSX directly into closure data"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 04 - split-2-teams into closure data"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 05 - AST teams-ledger-calculation"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 06 - AST UAT closure run 03_2024"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 09 - AST plan processing"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 10 - AST plan timeout error"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 11 - AST 04 closure"
#./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 12 - XPHR 04 closure"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 13 - sharepoint-based file processing"  || handle_error "test13"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 14 - sharepoint-based bytes processing" || handle_error "test14"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 15 - Aston ITM - SXL import initialized by a call" || handle_error "test15"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 15B - Aston ITM - SXL import initialized by a call, moved to class this time" || handle_error "test15B"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 16 - ISSK WR - gen 2" || handle_error "test16"
./gradlew integrationTest --tests ars.rockycube.QualityShadowTester."test 17 - AST full pipeline" || handle_error "test17"
./gradlew integrationTest --tests ars.rockycube.AttachmentProcTester || handle_error "AttachmentProcTester"
./gradlew integrationTest --tests ars.rockycube.DirectProcCaller || handle_error "DirectProcCaller"
./gradlew integrationTest --tests ars.rockycube.ProcCaller || handle_error "ProcCaller"
./gradlew integrationTest --tests ars.rockycube.AddClosureTester || handle_error "AddClosureTester"
./gradlew integrationTest --tests ars.rockycube.ShortrackTester || handle_error "ShortrackTester"

echo "All tasks executed successfully"
popd
popd
popd