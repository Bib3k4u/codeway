#!/usr/bin/env bash
# One-time setup so .github/workflows/deploy-backend.yml can deploy to the EC2 server. Safe to re-run.
#   ./deploy/ec2/setup-github-deploy.sh [owner/repo]      (default Bib3k4u/codeway)
#
# Creates:
#   - the GitHub OIDC identity provider in IAM (if missing)
#   - IAM role codeway-github-deploy, assumable only by workflows on this repo's master branch, allowed only to
#     describe instances and open/close ingress on the codeway-sg security group
#   - an SSH deploy key for ec2-user on the server (separate from your own ~/.ssh/codeway.pem, so it can be revoked)
#   - repo variable AWS_ROLE_ARN and repo secret EC2_SSH_KEY (via the gh CLI)
# Needs: aws CLI signed in, gh CLI signed in with admin access to the repo, and the server from launch.sh running.
set -euo pipefail
REPO="${1:-Bib3k4u/codeway}"
export AWS_REGION="${AWS_REGION:-us-east-2}" AWS_PAGER=""
ROLE=codeway-github-deploy SG_NAME=codeway-sg
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
say() { printf '==> %s\n' "$*"; }

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
OIDC_ARN="arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com"
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
    --client-id-list sts.amazonaws.com >/dev/null
  say "Created GitHub OIDC provider"
fi

SG="$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$SG_NAME" --query 'SecurityGroups[0].GroupId' --output text)"
[ "$SG" != None ] || { echo "!! No $SG_NAME security group. Run ./deploy/ec2/launch.sh first."; exit 1; }

TRUST=$(cat <<EOF
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"$OIDC_ARN"},
 "Action":"sts:AssumeRoleWithWebIdentity","Condition":{
  "StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com",
                  "token.actions.githubusercontent.com:sub":"repo:$REPO:ref:refs/heads/master"}}}]}
EOF
)
POLICY=$(cat <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["ec2:DescribeInstances","ec2:DescribeSecurityGroups"],"Resource":"*"},
 {"Effect":"Allow","Action":["ec2:AuthorizeSecurityGroupIngress","ec2:RevokeSecurityGroupIngress"],
  "Resource":"arn:aws:ec2:$AWS_REGION:$ACCOUNT:security-group/$SG"}]}
EOF
)
if aws iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE" --policy-document "$TRUST"
else
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document "$TRUST" \
    --description "GitHub Actions deploy for $REPO" >/dev/null
  say "Created IAM role $ROLE"
fi
aws iam put-role-policy --role-name "$ROLE" --policy-name codeway-deploy --policy-document "$POLICY"
ROLE_ARN="arn:aws:iam::$ACCOUNT:role/$ROLE"

# Deploy key: generated in a temp dir, public half installed on the server, private half only in the GitHub secret.
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
ssh-keygen -q -t ed25519 -N '' -C codeway-github-deploy -f "$TMP/deploy_key"
HOST="$("$ROOT/deploy/ec2/launch.sh" ip)"
ssh -i "$HOME/.ssh/codeway.pem" -o StrictHostKeyChecking=accept-new "ec2-user@$HOST" \
  "grep -v 'codeway-github-deploy' ~/.ssh/authorized_keys > ~/.ssh/ak.tmp || true; cat >> ~/.ssh/ak.tmp; mv ~/.ssh/ak.tmp ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys" \
  < "$TMP/deploy_key.pub"
say "Installed deploy key on $HOST (replaces any previous one)"

gh variable set AWS_ROLE_ARN --repo "$REPO" --body "$ROLE_ARN"
gh secret set EC2_SSH_KEY --repo "$REPO" < "$TMP/deploy_key"
say "Set AWS_ROLE_ARN and EC2_SSH_KEY on $REPO"
