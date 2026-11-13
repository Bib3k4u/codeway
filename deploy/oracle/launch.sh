#!/usr/bin/env bash
# Create (or reuse) the Codeway backend server on Oracle Cloud Always Free, then deploy to it.
#
#   ./deploy/oracle/launch.sh              create network + server if needed, then deploy (safe to re-run)
#   ./deploy/oracle/launch.sh --postgres   same, and also run Postgres on the server (see postgres.sh)
#   ./deploy/oracle/launch.sh ip|url       print the public IP / the HTTPS backend URL
#   ./deploy/oracle/launch.sh ssh          open a shell on the server
#   ./deploy/oracle/launch.sh myip         allow SSH from your current IP again
#   ./deploy/oracle/launch.sh stop|start   stop / start the server (the public IP survives a stop)
#   ./deploy/oracle/launch.sh destroy      terminate the server and delete the network (asks first)
#
# Always Free shape: VM.Standard.A1.Flex (Ampere ARM) with 2 OCPUs and 12 GB, Ubuntu 24.04, 50 GB boot volume.
# Needs the OCI CLI signed in:  oci session authenticate --region us-chicago-1 --profile-name codeway
set -euo pipefail
export OCI_CLI_PROFILE="${OCI_CLI_PROFILE:-codeway}" OCI_CLI_AUTH="${OCI_CLI_AUTH:-security_token}" SUPPRESS_LABEL_WARNING=True
OCPUS="${OCPUS:-2}" MEM_GB="${MEM_GB:-12}" BOOT_GB="${BOOT_GB:-50}"
NAME=codeway-backend
KEY_FILE="$HOME/.ssh/codeway-oci"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
say() { printf '==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }
q() { oci "$@" 2>/dev/null; }

oci iam region list >/dev/null 2>&1 || die "OCI CLI is not signed in. Run: oci session authenticate --region us-chicago-1 --profile-name codeway"
T="$(grep -A8 "\[$OCI_CLI_PROFILE\]" "$HOME/.oci/config" | grep '^tenancy' | cut -d= -f2 | tr -d ' ')"
[ -n "$T" ] || die "No tenancy in ~/.oci/config for profile $OCI_CLI_PROFILE"
SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8)

instance_id() {
  q compute instance list --compartment-id "$T" --display-name "$NAME" --query \
    'data[?"lifecycle-state"!=`TERMINATED` && "lifecycle-state"!=`TERMINATING`] | [0].id' --raw-output | grep -v '^null$' || true
}
public_ip() {
  q compute instance list-vnics --instance-id "$1" --query 'data[0]."public-ip"' --raw-output | grep -v '^null$' || true
}
by_name() { # by_name <resource-cmd...> : first non-terminated resource named $NAME-<suffix>
  q "$@" --compartment-id "$T" --query "data[?\"lifecycle-state\"=='AVAILABLE'] | [0].id" --raw-output | grep -v '^null$' || true
}
my_ip() { curl -fs https://checkip.amazonaws.com | tr -d '[:space:]'; }

security_rules() { # ingress: SSH from $1 only; HTTP/HTTPS from anywhere (Caddy serves the backend on 443)
  cat <<JSON
[{"protocol":"6","source":"$1/32","isStateless":false,"description":"ssh-admin","tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},
 {"protocol":"6","source":"0.0.0.0/0","isStateless":false,"description":"http","tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},
 {"protocol":"6","source":"0.0.0.0/0","isStateless":false,"description":"https","tcpOptions":{"destinationPortRange":{"min":443,"max":443}}}]
JSON
}

POSTGRES=0
case "${1:-up}" in
  ip)  id="$(instance_id)"; [ -n "$id" ] || die "No server found."; public_ip "$id"; exit 0 ;;
  url) id="$(instance_id)"; [ -n "$id" ] || die "No server found."; ip="$(public_ip "$id")"; [ -n "$ip" ] || die "Server has no public IP (stopped?)"
       echo "https://${ip//./-}.sslip.io"; exit 0 ;;
  ssh) id="$(instance_id)"; [ -n "$id" ] || die "No server found."; exec "${SSH[@]}" "ubuntu@$(public_ip "$id")" ;;
  myip)
    sl="$(by_name network security-list list --display-name "$NAME-sl")"; [ -n "$sl" ] || die "No security list."
    oci network security-list update --security-list-id "$sl" --ingress-security-rules "$(security_rules "$(my_ip)")" --force >/dev/null
    say "SSH now allowed from $(my_ip)"; exit 0 ;;
  stop)  id="$(instance_id)"; [ -n "$id" ] || die "No server found."; oci compute instance action --instance-id "$id" --action SOFTSTOP --wait-for-state STOPPED >/dev/null; say "Stopped"; exit 0 ;;
  start) id="$(instance_id)"; [ -n "$id" ] || die "No server found."; oci compute instance action --instance-id "$id" --action START --wait-for-state RUNNING >/dev/null
         say "Running at https://$(public_ip "$id" | tr . -).sslip.io"; exit 0 ;;
  destroy)
    id="$(instance_id)"
    read -r -p "Terminate ${id:-<no server>} (boot volume deleted) and delete the $NAME network? Type yes: " ok; [ "$ok" = yes ] || die "Cancelled."
    [ -z "$id" ] || { oci compute instance terminate --instance-id "$id" --preserve-boot-volume false --force --wait-for-state TERMINATED >/dev/null; say "Server terminated"; }
    sn="$(by_name network subnet list --display-name "$NAME-subnet")"; [ -z "$sn" ] || oci network subnet delete --subnet-id "$sn" --force --wait-for-state TERMINATED >/dev/null
    vcn="$(by_name network vcn list --display-name "$NAME-vcn")"
    if [ -n "$vcn" ]; then
      rt="$(q network vcn get --vcn-id "$vcn" --query 'data."default-route-table-id"' --raw-output)"
      oci network route-table update --rt-id "$rt" --route-rules '[]' --force >/dev/null
      ig="$(by_name network internet-gateway list --vcn-id "$vcn" --display-name "$NAME-ig")"; [ -z "$ig" ] || oci network internet-gateway delete --ig-id "$ig" --force --wait-for-state TERMINATED >/dev/null
      sl="$(by_name network security-list list --vcn-id "$vcn" --display-name "$NAME-sl")"; [ -z "$sl" ] || oci network security-list delete --security-list-id "$sl" --force --wait-for-state TERMINATED >/dev/null
      oci network vcn delete --vcn-id "$vcn" --force --wait-for-state TERMINATED >/dev/null
    fi
    say "Network deleted"; exit 0 ;;
  --postgres) POSTGRES=1 ;;
  up) ;;
  *) die "Unknown command: $1" ;;
esac

[ -f "$ROOT/deploy/oracle/codeway.env" ] || die "deploy/oracle/codeway.env is missing (cp deploy/oracle/codeway.env.example and fill it in)."

# ---- SSH key (separate from the AWS one) ----
if [ ! -f "$KEY_FILE" ]; then ssh-keygen -q -t ed25519 -N '' -C codeway-oci -f "$KEY_FILE"; say "Created SSH key $KEY_FILE"; fi

# ---- network: VCN, internet gateway, default route, security list, public subnet ----
vcn="$(by_name network vcn list --display-name "$NAME-vcn")"
if [ -z "$vcn" ]; then
  vcn="$(q network vcn create --compartment-id "$T" --cidr-blocks '["10.0.0.0/16"]' --display-name "$NAME-vcn" --dns-label codeway \
         --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
  say "Created VCN"
fi
ig="$(by_name network internet-gateway list --vcn-id "$vcn" --display-name "$NAME-ig")"
if [ -z "$ig" ]; then
  ig="$(q network internet-gateway create --compartment-id "$T" --vcn-id "$vcn" --is-enabled true --display-name "$NAME-ig" \
        --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
fi
rt="$(q network vcn get --vcn-id "$vcn" --query 'data."default-route-table-id"' --raw-output)"
oci network route-table update --rt-id "$rt" --route-rules "[{\"destination\":\"0.0.0.0/0\",\"destinationType\":\"CIDR_BLOCK\",\"networkEntityId\":\"$ig\"}]" --force >/dev/null
sl="$(by_name network security-list list --vcn-id "$vcn" --display-name "$NAME-sl")"
if [ -z "$sl" ]; then
  sl="$(q network security-list create --compartment-id "$T" --vcn-id "$vcn" --display-name "$NAME-sl" \
        --egress-security-rules '[{"protocol":"all","destination":"0.0.0.0/0","isStateless":false}]' \
        --ingress-security-rules "$(security_rules "$(my_ip)")" --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
else
  oci network security-list update --security-list-id "$sl" --ingress-security-rules "$(security_rules "$(my_ip)")" --force >/dev/null
fi
sn="$(by_name network subnet list --vcn-id "$vcn" --display-name "$NAME-subnet")"
if [ -z "$sn" ]; then
  sn="$(q network subnet create --compartment-id "$T" --vcn-id "$vcn" --cidr-block 10.0.1.0/24 --display-name "$NAME-subnet" --dns-label app \
        --route-table-id "$rt" --security-list-ids "[\"$sl\"]" --prohibit-public-ip-on-vnic false --wait-for-state AVAILABLE --query 'data.id' --raw-output)"
fi
say "Network ready (SSH from $(my_ip) only; 80/443 open)"

# ---- server: Always Free A1, trying each availability domain (free ARM capacity comes and goes) ----
id="$(instance_id)"
if [ -z "$id" ]; then
  img="$(q compute image list --compartment-id "$T" --operating-system "Canonical Ubuntu" --operating-system-version "24.04" \
         --shape VM.Standard.A1.Flex --sort-by TIMECREATED --sort-order DESC --limit 1 --query 'data[0].id' --raw-output)"
  [ -n "$img" ] || die "No Ubuntu 24.04 ARM image found"
  for ad in $(q iam availability-domain list --compartment-id "$T" --query 'data[].name' --raw-output | tr -d '[]",'); do
    say "Trying $ad"
    if out="$(oci compute instance launch --compartment-id "$T" --availability-domain "$ad" --display-name "$NAME" \
         --shape VM.Standard.A1.Flex --shape-config "{\"ocpus\":$OCPUS,\"memoryInGBs\":$MEM_GB}" \
         --image-id "$img" --boot-volume-size-in-gbs "$BOOT_GB" --subnet-id "$sn" --assign-public-ip true \
         --metadata "{\"ssh_authorized_keys\":\"$(cat "$KEY_FILE.pub")\"}" --wait-for-state RUNNING --query 'data.id' --raw-output 2>&1)"; then
      id="$(echo "$out" | tail -1)"; break
    fi
    echo "$out" | grep -qi "capacity" && { say "No free ARM capacity in $ad"; continue; }
    die "Launch failed: $(echo "$out" | tail -3)"
  done
  [ -n "$id" ] || die "No free ARM capacity in any availability domain right now. Try again in a while (it frees up often)."
  say "Launched $NAME ($OCPUS OCPU, ${MEM_GB} GB, Ubuntu 24.04 ARM)"
fi
ip="$(public_ip "$id")"
say "Public IP $ip"
for _ in $(seq 1 60); do "${SSH[@]}" -q "ubuntu@$ip" true 2>/dev/null && break; sleep 5; done
"${SSH[@]}" -q "ubuntu@$ip" true || die "SSH did not come up. If your IP changed: ./deploy/oracle/launch.sh myip"

# ---- with --postgres: a private bucket for backups and a write-only upload URL for the server ----
if [ "$POSTGRES" = 1 ] && [ ! -f "$ROOT/deploy/oracle/backup.env" ]; then
  ns="$(q os ns get --query data --raw-output)"
  q os bucket get --bucket-name codeway-backups >/dev/null || q os bucket create --compartment-id "$T" --name codeway-backups --public-access-type NoPublicAccess >/dev/null
  par="$(q os preauth-request create --bucket-name codeway-backups --name codeway-backup-upload --access-type AnyObjectWrite \
         --time-expires "$(date -u -v+1y +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 year' +%Y-%m-%dT%H:%M:%SZ)" --query 'data."access-uri"' --raw-output)"
  region="$(grep -A8 "\[$OCI_CLI_PROFILE\]" "$HOME/.oci/config" | grep '^region' | cut -d= -f2 | tr -d ' ')"
  printf 'BACKUP_PAR_URL=https://objectstorage.%s.oraclecloud.com%s\n' "$region" "$par" > "$ROOT/deploy/oracle/backup.env"
  chmod 600 "$ROOT/deploy/oracle/backup.env"
  say "Backup bucket codeway-backups ready (namespace $ns); write-only upload URL saved to deploy/oracle/backup.env"
fi

"$ROOT/deploy/oracle/deploy.sh" "ubuntu@$ip" "$KEY_FILE" $([ "$POSTGRES" = 1 ] && echo --postgres)
cat <<EOF

==> Server ready: https://${ip//./-}.sslip.io
    Set BACKEND_URL to that in Vercel (and frontend/.env.local), then redeploy the frontend.
EOF
