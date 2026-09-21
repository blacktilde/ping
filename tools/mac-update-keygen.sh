#!/bin/sh
# Generates the Ed25519 keypair that signs macOS updates.
#
# The private half goes into the MAC_UPDATE_SIGNING_KEY repository secret and nowhere else —
# it is what lets a build claim to be an official release. The public half replaces
# PUBLIC_KEY_PEM in desktop/src/main/mac-update.ts, where it is the check every downloaded
# update has to pass. Running this again retires every signature made with the old key, which
# is how the key is rotated if it ever leaks.
set -eu

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

openssl genpkey -algorithm ed25519 -out "$dir/private.pem" 2>/dev/null
openssl pkey -in "$dir/private.pem" -pubout -out "$dir/public.pem"

echo "=== Private key — paste into the MAC_UPDATE_SIGNING_KEY repository secret ==="
cat "$dir/private.pem"
echo "=== Public key — paste into PUBLIC_KEY_PEM in desktop/src/main/mac-update.ts ==="
cat "$dir/public.pem"
