#!/bin/bash

# Convert relative path to absolute path
OUTPUT_DIR=$(realpath "${1:-"./config/certs"}")

# Check if openssl is installed
if ! command -v openssl &> /dev/null; then
    echo "Error: openssl is not installed"
    echo "Please install openssl to generate SSL certificates for OpenSearch"
    echo "On Ubuntu/Debian: sudo apt-get install openssl"
    echo "On CentOS/RHEL: sudo yum install openssl"
    echo "On macOS: brew install openssl"
    exit 1
fi

# Create output directory if it doesn't exist and set permissions
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"

# Create root CA if it doesn't exist
if [ ! -f "$OUTPUT_DIR/root-ca-key.pem" ] || [ ! -f "$OUTPUT_DIR/root-ca.pem" ]; then
    openssl genrsa -out "$OUTPUT_DIR/root-ca-key.pem" 2048
    chmod 600 "$OUTPUT_DIR/root-ca-key.pem"
    openssl req -new -x509 -sha256 -key "$OUTPUT_DIR/root-ca-key.pem" \
        -subj "/C=US/ST=CA/L=San Francisco/O=Organization/CN=Root CA" \
        -out "$OUTPUT_DIR/root-ca.pem" -days 365000
    chmod 600 "$OUTPUT_DIR/root-ca.pem"
fi

# Create admin certificate if it doesn't exist
if [ ! -f "$OUTPUT_DIR/admin-key.pem" ] || [ ! -f "$OUTPUT_DIR/admin.pem" ]; then
    openssl genrsa -out "$OUTPUT_DIR/admin-key-temp.pem" 2048
    openssl pkcs8 -inform PEM -outform PEM -in "$OUTPUT_DIR/admin-key-temp.pem" \
        -topk8 -nocrypt -v1 PBE-SHA1-3DES -out "$OUTPUT_DIR/admin-key.pem"
    chmod 600 "$OUTPUT_DIR/admin-key.pem"
    openssl req -new -key "$OUTPUT_DIR/admin-key.pem" \
        -subj "/C=US/ST=CA/L=San Francisco/O=Organization/CN=admin" \
        -out "$OUTPUT_DIR/admin.csr"
    openssl x509 -req -in "$OUTPUT_DIR/admin.csr" \
        -CA "$OUTPUT_DIR/root-ca.pem" \
        -CAkey "$OUTPUT_DIR/root-ca-key.pem" \
        -CAcreateserial -sha256 -out "$OUTPUT_DIR/admin.pem" -days 365000
    chmod 600 "$OUTPUT_DIR/admin.pem"
fi

# Clean up temporary files
[ -f "$OUTPUT_DIR/admin-key-temp.pem" ] && rm "$OUTPUT_DIR/admin-key-temp.pem"
[ -f "$OUTPUT_DIR/admin.csr" ] && rm "$OUTPUT_DIR/admin.csr"
[ -f "$OUTPUT_DIR/root-ca.srl" ] && rm "$OUTPUT_DIR/root-ca.srl"

echo "Root CA and admin certificates generated successfully in $OUTPUT_DIR directory"
echo "Please ensure your config has the appropriate certificate paths"
