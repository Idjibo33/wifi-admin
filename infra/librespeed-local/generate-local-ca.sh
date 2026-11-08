#!/usr/bin/env bash
# Genere une autorite de certification LOCALE de dev + un certificat serveur SAN
# pour la validation HTTPS Pixel <-> Ubuntu (Mission 14).
# La cle privee de la CA et la cle privee du serveur restent LOCALES (jamais commitees).
# Seul le certificat PUBLIC de la CA est copie dans app/src/debug/res/raw/ (debug-only).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TLS_DIR="$SCRIPT_DIR/tls"
CA_CERT="$TLS_DIR/ca.crt"
CA_KEY="$TLS_DIR/ca.key"
SRV_CERT="$TLS_DIR/cert.pem"
SRV_KEY="$TLS_DIR/key.pem"
DAYS=365
IP_LOOPBACK="127.0.0.1"
IP_LAN="${LAN_IP:-192.168.1.174}"
DNS_NAME="${DNS_NAME:-localhost}"

if [[ -f "$CA_KEY" ]]; then
  echo "Refuse: $CA_KEY existe deja. Supprimez-le explicitement pour regenerer." >&2
  exit 1
fi

mkdir -p "$TLS_DIR"
chmod 700 "$TLS_DIR"

# CA locale de dev (outil local uniquement, jamais commitée)
openssl req -x509 -newkey rsa:2048 -sha256 -days "$DAYS" -nodes \
  -keyout "$CA_KEY" -out "$CA_CERT" \
  -subj "/CN=WiFi Admin Dev CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
chmod 600 "$CA_KEY"

# Certificat serveur avec SAN IP loopback + IP LAN + DNS localhost
openssl req -newkey rsa:2048 -nodes -keyout "$SRV_KEY" -out "$TLS_DIR/srv.csr" \
  -subj "/CN=$DNS_NAME"
chmod 600 "$SRV_KEY"

cat > "$TLS_DIR/srv.ext" <<EOF
subjectAltName=IP:$IP_LOOPBACK,IP:$IP_LAN,DNS:$DNS_NAME
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF

openssl x509 -req -in "$TLS_DIR/srv.csr" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$SRV_CERT" -days 90 -sha256 -extfile "$TLS_DIR/srv.ext"

echo "Certificats generes dans $TLS_DIR"
echo "  CA publique : $CA_CERT"
echo "  Serveur (SAN 127.0.0.1, $IP_LAN, localhost) : $SRV_CERT"
echo ""
echo "Prochaine etape (debug uniquement) :"
echo "  cp \"$CA_CERT\" app/src/debug/res/raw/local_ca.crt"
echo "  git add app/src/debug/res/raw/local_ca.crt   # certificat PUBLIC uniquement"
echo "  # Verifiez que ca.key / key.pem ne sont PAS ajoutes a git (voir .gitignore)."
echo ""
echo "Demarrage TLS dev (apres autorisation) :"
echo "  docker compose --profile tls -f infra/librespeed-local/compose.yaml up -d"
