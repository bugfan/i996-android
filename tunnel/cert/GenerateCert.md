openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout key.pem \
    -out cert.pem \
    -days 36500 \
    -subj "/C=CN/ST=Beijing/L=Internet/O=Safety/OU=X/CN=i996/emailAddress=your@example.com" \
    -addext "subjectAltName=DNS:i996"
