#!/bin/bash

# Convert relative path to absolute path
OUTPUT_DIR=$(realpath "${1:-"./config/certs"}")

# Create root CA if it doesn't exist
if [ ! -f "$OUTPUT_DIR/root-ca-key.pem" ] || [ ! -f "$OUTPUT_DIR/root-ca.pem" ]; then
    rm "$OUTPUT_DIR/root-ca-key.pem"
    rm "$OUTPUT_DIR/root-ca.pem"
fi

# Create admin certificate if it doesn't exist
if [ ! -f "$OUTPUT_DIR/admin-key.pem" ] || [ ! -f "$OUTPUT_DIR/admin.pem" ]; then
    rm "$OUTPUT_DIR/admin-key.pem"
    rm "$OUTPUT_DIR/admin.pem"
fi

echo "Root CA and admin certificates removed from $OUTPUT_DIR"
# Get parent directory of current script
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
. $SCRIPT_DIR/autogen_selfsigned_certs.sh
