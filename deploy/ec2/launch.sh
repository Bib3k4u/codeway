#!/usr/bin/env bash
# Create (or reuse) the Codeway EC2 test server from the terminal, then deploy to it. No AWS console needed.
#
#   ./deploy/ec2/launch.sh            create the server if needed, then deploy (safe to re-run)
#   ./deploy/ec2/launch.sh ip         print the server's public IP
#   ./deploy/ec2/launch.sh myip       allow your current IP again (after your network changes)
#   ./deploy/ec2/launch.sh ssh        open a shell on the server
#   ./deploy/ec2/launch.sh stop       stop the server (no compute charges while stopped; the IP changes on start)
#   ./deploy/ec2/launch.sh start      start it again and print the new IP
#   ./deploy/ec2/launch.sh destroy    terminate the server and delete its security group and key pair
#
# Settings (environment variables): AWS_REGION (default us-east-2, same region as the Neon database),
# INSTANCE_TYPE (default t2.micro: the free-tier type for this account), AWS_PROFILE (optional).
set -euo pipefail
export AWS_REGION="${AWS_REGION:-us-east-2}" AWS_PAGER=""
TYPE="${INSTANCE_TYPE:-t2.micro}"
NAME=codeway-backend SG_NAME=codeway-sg KEY_NAME=codeway
KEY_FILE="$HOME/.ssh/codeway.pem"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SSH=(ssh -i "$KEY_FILE" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=5)

say() { printf '==> %s\n' "$*"; }
die() { printf '!! %s\n' "$*" >&2; exit 1; }

aws sts get-caller-identity >/dev/null 2>&1 || die "AWS CLI is not signed in. Run: aws login   (or: aws configure)"

instance_id() {
  aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=$NAME" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[0].InstanceId' --output text | awk '{print $1}' | grep -v None || true
}
public_ip() { aws ec2 describe-instances --instance-ids "$1" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text; }
sg_id() {
  aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null | grep -v None || true
}
allow_my_ip() {
  local sg="$1" ip; ip="$(curl -fs https://checkip.amazonaws.com | tr -d '[:space:]')"
  [ -n "$ip" ] || die "Could not detect your public IP."
  for port in 22 8080; do
    aws ec2 authorize-security-group-ingress --group-id "$sg" \
      --ip-permissions "IpProtocol=tcp,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$ip/32,Description=codeway-admin}]" \
      >/dev/null 2>&1 || true   # already allowed
  done
  say "Ports 22 and 8080 open to your IP $ip"
}
wait_ssh() {
  say "Waiting for SSH on $1"
  for _ in $(seq 1 40); do "${SSH[@]}" -q "ec2-user@$1" true 2>/dev/null && return 0; sleep 5; done
  die "SSH did not come up. Check: ./deploy/ec2/launch.sh myip"
}

case "${1:-up}" in
  ip)    id="$(instance_id)"; [ -n "$id" ] || die "No server found."; public_ip "$id"; exit 0 ;;
  myip)  sg="$(sg_id)"; [ -n "$sg" ] || die "No security group found."; allow_my_ip "$sg"; exit 0 ;;
  ssh)   id="$(instance_id)"; [ -n "$id" ] || die "No server found."; exec "${SSH[@]}" "ec2-user@$(public_ip "$id")" ;;
  stop)  id="$(instance_id)"; [ -n "$id" ] || die "No server found."
         aws ec2 stop-instances --instance-ids "$id" >/dev/null; say "Stopping $id"; exit 0 ;;
  start) id="$(instance_id)"; [ -n "$id" ] || die "No server found."
         aws ec2 start-instances --instance-ids "$id" >/dev/null; aws ec2 wait instance-running --instance-ids "$id"
         say "Running. New IP: $(public_ip "$id")  (the service starts by itself)"; exit 0 ;;
  destroy)
    id="$(instance_id)"
    read -r -p "Terminate ${id:-<no instance>}, and delete security group $SG_NAME and key pair $KEY_NAME? Type yes: " ok
    [ "$ok" = yes ] || die "Cancelled."
    if [ -n "$id" ]; then aws ec2 terminate-instances --instance-ids "$id" >/dev/null; say "Terminating $id"
      aws ec2 wait instance-terminated --instance-ids "$id"; fi
    alloc="$(aws ec2 describe-addresses --filters Name=tag:Name,Values=codeway-backend-ip --query 'Addresses[0].AllocationId' --output text)"
    [ "$alloc" = None ] || { aws ec2 release-address --allocation-id "$alloc"; say "Released Elastic IP"; }
    sg="$(sg_id)"; [ -z "$sg" ] || { aws ec2 delete-security-group --group-id "$sg"; say "Deleted $SG_NAME"; }
    aws ec2 delete-key-pair --key-name "$KEY_NAME" >/dev/null 2>&1 && rm -f "$KEY_FILE" && say "Deleted key pair"
    exit 0 ;;
  up) ;;
  *) die "Unknown command: $1" ;;
esac

[ -f "$ROOT/deploy/ec2/codeway.env" ] || die "deploy/ec2/codeway.env is missing (copy codeway.env.example and fill it in)."
say "Region $AWS_REGION, type $TYPE"

# Key pair: created once, private key kept at ~/.ssh/codeway.pem
if ! aws ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
  [ ! -e "$KEY_FILE" ] || die "$KEY_FILE exists but AWS has no key pair '$KEY_NAME'. Move the file away and re-run."
  mkdir -p "$HOME/.ssh"
  aws ec2 create-key-pair --key-name "$KEY_NAME" --key-type rsa --query KeyMaterial --output text > "$KEY_FILE"
  chmod 400 "$KEY_FILE"; say "Created key pair, saved to $KEY_FILE"
elif [ ! -f "$KEY_FILE" ]; then
  die "AWS key pair '$KEY_NAME' exists but $KEY_FILE is missing. Run: ./deploy/ec2/launch.sh destroy, then re-run."
fi

# Security group in the default VPC: SSH and 8080 from your IP only
sg="$(sg_id)"
if [ -z "$sg" ]; then
  vpc="$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)"
  [ "$vpc" != None ] || die "No default VPC in $AWS_REGION. Create one with: aws ec2 create-default-vpc"
  sg="$(aws ec2 create-security-group --group-name "$SG_NAME" --description "Codeway backend (test)" \
        --vpc-id "$vpc" --query GroupId --output text)"
  say "Created security group $sg"
fi
allow_my_ip "$sg"

# Instance: reuse if it exists
id="$(instance_id)"
if [ -z "$id" ]; then
  ami="$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
         --query Parameter.Value --output text)"
  id="$(aws ec2 run-instances --image-id "$ami" --instance-type "$TYPE" --key-name "$KEY_NAME" \
        --security-group-ids "$sg" --credit-specification CpuCredits=standard \
        --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=20,VolumeType=gp3,DeleteOnTermination=true}' \
        --metadata-options HttpTokens=required \
        --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME}]" \
        --query 'Instances[0].InstanceId' --output text)"
  say "Launched $id ($TYPE, Amazon Linux 2023)"
else
  state="$(aws ec2 describe-instances --instance-ids "$id" --query 'Reservations[0].Instances[0].State.Name' --output text)"
  say "Reusing $id ($state)"
  [ "$state" != stopped ] || aws ec2 start-instances --instance-ids "$id" >/dev/null
fi
aws ec2 wait instance-running --instance-ids "$id"
ip="$(public_ip "$id")"
say "Public IP $ip"
wait_ssh "$ip"

"$ROOT/deploy/ec2/deploy.sh" "ec2-user@$ip" "$KEY_FILE"

cat <<EOF

==> Server ready: http://$ip:8080
    Smoke test:  ./deploy/ec2/smoke-test.sh http://$ip:8080
    Frontend:    cd frontend && BACKEND_URL=http://$ip:8080 npm run dev
    Pause it:    ./deploy/ec2/launch.sh stop
EOF
