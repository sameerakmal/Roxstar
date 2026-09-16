#!/usr/bin/env bash
# One-time Azure setup for the Roxstar backend.
#
# Creates the container registry, the managed identity that pulls from it, the
# Container Apps environment, the Container App itself, and the GitHub OIDC federated
# credential that lets Actions deploy without an Azure client secret.
#
# Idempotent: re-running is safe. Creation steps tolerate an already-exists outcome
# and nothing else — any other az failure aborts with the real error rather than being
# swallowed.
#
# Every command passes --subscription and --resource-group explicitly, so the script
# never depends on (or mutates) your ambient `az configure` state.
#
# Usage:
#   export GITHUB_REPOSITORY="your-org/your-repo"
#   export ACR_NAME="roxstaracr1234"          # must be globally unique
#   ./infrastructure/azure-setup.sh
#
# The MongoDB connection string is read from a silent prompt, never from a
# command-line argument, so it stays out of your shell history.

set -euo pipefail

# Git Bash / MSYS2 rewrites any value that looks like a POSIX path into a Windows
# path when it crosses into a native process, so an ARM id like
# /subscriptions/<guid>/... arrives at az.exe as C:/Program Files/Git/subscriptions/...
# The result is a non-empty but invalid id, which surfaces as the confusing
# "Invalid environmentId specified. Environment not found".
# These two variables disable that rewriting; both are inert on Linux and macOS.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

RESOURCE_GROUP="${AZURE_RESOURCE_GROUP:-roxstar-backend-rg}"
LOCATION="${AZURE_REGION:-centralindia}"
CONTAINERAPP_NAME="${CONTAINERAPP_NAME:-roxstar-backend}"
ENVIRONMENT_NAME="${CONTAINERAPP_ENVIRONMENT:-roxstar-env}"
IDENTITY_NAME="${IDENTITY_NAME:-roxstar-backend-id}"
APP_REGISTRATION_NAME="${APP_REGISTRATION_NAME:-roxstar-github-deployer}"

# ACR names are GLOBALLY unique across Azure, so there is no safe default. The name
# must be supplied and is validated for availability before anything else is created.
ACR_NAME="${ACR_NAME:-}"

# Bootstrap image. The first revision runs this and will report unhealthy, because it
# does not serve /ready — that is expected. The first deployment replaces it.
BOOTSTRAP_IMAGE="${BOOTSTRAP_IMAGE:-mcr.microsoft.com/azuredocs/containerapps-helloworld:latest}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/containerapp.yaml"

# --------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------

fail() {
  echo >&2
  echo "ERROR: $*" >&2
  exit 1
}

# Runs an az create command and tolerates ONLY an already-exists outcome. Every other
# failure is reported with the real error and aborts, so a permission or quota problem
# can never be mistaken for an existing resource.
create_if_absent() {
  local description="$1"
  shift

  # Captured on the left of || so a failure does not trip errexit, and without
  # toggling shell options globally the way `set +e` would.
  local output status=0
  output="$("$@" 2>&1)" || status=$?

  if ((status == 0)); then
    echo "    created ${description}"
    return 0
  fi

  if grep -qiE 'already exists|AlreadyExists|Conflict' <<<"${output}"; then
    echo "    ${description} already exists"
    return 0
  fi

  echo "    failed to create ${description}" >&2
  echo "${output}" >&2
  fail "az exited with status ${status} while creating ${description}"
}

# Captures an ARM resource id and proves it is actually one.
#
# Two failure modes are handled: a query that returned nothing, and a value a shell
# rewrote into a Windows path (see the MSYS note above). Anything preceding
# /subscriptions/ is stripped, and a value that still does not look like a resource id
# aborts the script rather than being handed to az, where it surfaces as a misleading
# "not found".
resource_id() {
  local description="$1"
  shift

  local value
  value="$("$@" 2>/dev/null || true)"
  value="${value%$'\r'}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"

  case "${value}" in
    */subscriptions/*)
      value="/subscriptions/${value#*/subscriptions/}"
      ;;
  esac

  [[ -n "${value}" ]] || fail "Could not resolve the ${description}. Does it exist in this subscription?"

  [[ "${value}" == /subscriptions/* ]] || fail "The ${description} is not a valid Azure resource id:

       ${value}

       Expected a value beginning with /subscriptions/."

  printf '%s' "${value}"
}

# Converts a shell path to one a native Windows process can open.
#
# Git Bash reports POSIX paths (/e/..., /tmp/...), and the MSYS_NO_PATHCONV settings
# above deliberately stop the shell from translating arguments — which is what the ARM
# resource ids need, but it also means python.exe and az.exe would receive a path they
# cannot open. cygpath -m produces E:/... with forward slashes, avoiding any
# backslash-escaping question.
#
# On Linux and macOS cygpath does not exist and the path is returned unchanged, so
# those platforms behave exactly as before.
to_native_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

cleanup() {
  # The rendered spec briefly holds the Atlas credentials.
  if [[ -n "${RENDERED:-}" && -f "${RENDERED}" ]]; then
    rm -f "${RENDERED}"
  fi
}
trap cleanup EXIT

# --------------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------------

command -v az >/dev/null 2>&1 || fail "Azure CLI not found on PATH."
[[ -f "${TEMPLATE}" ]] || fail "Missing template: ${TEMPLATE}"

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  fail "GITHUB_REPOSITORY must be set, for example: export GITHUB_REPOSITORY=your-org/your-repo"
fi

if [[ -z "${ACR_NAME}" ]]; then
  fail "ACR_NAME must be set. Registry names are globally unique across Azure, so pick
       something unlikely to collide, for example: export ACR_NAME=roxstaracr\$RANDOM"
fi

if [[ ! "${ACR_NAME}" =~ ^[a-zA-Z0-9]{5,50}$ ]]; then
  fail "ACR_NAME '${ACR_NAME}' is invalid: 5-50 alphanumeric characters, no hyphens."
fi

SUBSCRIPTION_ID="$(az account show --query id -o tsv 2>/dev/null)" \
  || fail "Not signed in. Run: az login"
TENANT_ID="$(az account show --query tenantId -o tsv)"

az group show --name "${RESOURCE_GROUP}" --subscription "${SUBSCRIPTION_ID}" >/dev/null 2>&1 \
  || fail "Resource group '${RESOURCE_GROUP}' not found in subscription ${SUBSCRIPTION_ID}."

echo "Subscription : ${SUBSCRIPTION_ID}"
echo "Tenant       : ${TENANT_ID}"
echo "Group        : ${RESOURCE_GROUP} (${LOCATION})"
echo "Registry     : ${ACR_NAME}"
echo "Repo         : ${GITHUB_REPOSITORY}"
echo

# --------------------------------------------------------------------------------
# Container registry
# --------------------------------------------------------------------------------

echo "==> Container registry"
if az acr show --name "${ACR_NAME}" --subscription "${SUBSCRIPTION_ID}" >/dev/null 2>&1; then
  echo "    registry '${ACR_NAME}' already exists in this subscription"
else
  # Distinguish "someone else owns this name" from "not created yet"; both look like a
  # failed show, but only one is recoverable by choosing another name.
  AVAILABLE="$(az acr check-name --name "${ACR_NAME}" --query nameAvailable -o tsv 2>/dev/null || echo "unknown")"
  if [[ "${AVAILABLE}" == "false" ]]; then
    fail "Registry name '${ACR_NAME}' is already taken globally by another Azure account.
       Choose a different ACR_NAME and re-run."
  fi

  create_if_absent "registry '${ACR_NAME}'" \
    az acr create \
    --name "${ACR_NAME}" \
    --resource-group "${RESOURCE_GROUP}" \
    --location "${LOCATION}" \
    --sku Basic \
    --subscription "${SUBSCRIPTION_ID}" \
    --only-show-errors
fi

ACR_ID="$(resource_id "registry id" \
  az acr show --name "${ACR_NAME}" --resource-group "${RESOURCE_GROUP}" \
  --subscription "${SUBSCRIPTION_ID}" --query id -o tsv)"
ACR_LOGIN_SERVER="$(az acr show --name "${ACR_NAME}" --resource-group "${RESOURCE_GROUP}" \
  --subscription "${SUBSCRIPTION_ID}" --query loginServer -o tsv)"

# Admin user stays disabled: pulls use the managed identity below, so no registry
# username or password ever exists.
az acr update --name "${ACR_NAME}" --resource-group "${RESOURCE_GROUP}" \
  --subscription "${SUBSCRIPTION_ID}" --admin-enabled false --only-show-errors >/dev/null

# --------------------------------------------------------------------------------
# Pull identity
# --------------------------------------------------------------------------------

echo "==> Managed identity for image pulls"
create_if_absent "identity '${IDENTITY_NAME}'" \
  az identity create \
  --name "${IDENTITY_NAME}" \
  --resource-group "${RESOURCE_GROUP}" \
  --location "${LOCATION}" \
  --subscription "${SUBSCRIPTION_ID}" \
  --only-show-errors

IDENTITY_RESOURCE_ID="$(resource_id "managed identity id" \
  az identity show --name "${IDENTITY_NAME}" \
  --resource-group "${RESOURCE_GROUP}" --subscription "${SUBSCRIPTION_ID}" --query id -o tsv)"
IDENTITY_PRINCIPAL_ID="$(az identity show --name "${IDENTITY_NAME}" \
  --resource-group "${RESOURCE_GROUP}" --subscription "${SUBSCRIPTION_ID}" --query principalId -o tsv)"

# AcrPull on this registry only — the identity can do nothing else anywhere.
echo "    granting AcrPull on ${ACR_NAME}"
az role assignment create \
  --assignee-object-id "${IDENTITY_PRINCIPAL_ID}" \
  --assignee-principal-type ServicePrincipal \
  --role AcrPull \
  --scope "${ACR_ID}" \
  --subscription "${SUBSCRIPTION_ID}" \
  --only-show-errors >/dev/null 2>&1 || echo "    AcrPull already assigned"

# --------------------------------------------------------------------------------
# Container Apps environment
# --------------------------------------------------------------------------------

echo "==> Container Apps environment"
create_if_absent "environment '${ENVIRONMENT_NAME}'" \
  az containerapp env create \
  --name "${ENVIRONMENT_NAME}" \
  --resource-group "${RESOURCE_GROUP}" \
  --location "${LOCATION}" \
  --subscription "${SUBSCRIPTION_ID}" \
  --only-show-errors

# The load-bearing id. The Container Apps YAML schema maps
# properties.managedEnvironmentId onto environmentId, so a malformed value here is
# exactly what produces "Invalid environmentId specified. Environment not found".
ENVIRONMENT_ID="$(resource_id "Container Apps environment id" \
  az containerapp env show --name "${ENVIRONMENT_NAME}" \
  --resource-group "${RESOURCE_GROUP}" --subscription "${SUBSCRIPTION_ID}" --query id -o tsv)"

# --------------------------------------------------------------------------------
# Container App
# --------------------------------------------------------------------------------

echo "==> Container App"
if az containerapp show --name "${CONTAINERAPP_NAME}" --resource-group "${RESOURCE_GROUP}" \
  --subscription "${SUBSCRIPTION_ID}" >/dev/null 2>&1; then

  echo "    app '${CONTAINERAPP_NAME}' already exists — leaving it untouched"
  echo "    rotate the database secret with:"
  echo "      az containerapp secret set --name ${CONTAINERAPP_NAME} \\"
  echo "        --resource-group ${RESOURCE_GROUP} --secrets mongodb-uri=<value>"
else
  echo "    Paste the MongoDB Atlas SRV string (input hidden), then press Enter:"
  read -rs MONGODB_URI
  echo
  [[ -n "${MONGODB_URI}" ]] || fail "No connection string provided."
  [[ "${MONGODB_URI}" == mongodb* ]] || fail "That does not look like a MongoDB connection string."

  # Rendered outside the repository, readable only by this user, removed on exit.
  RENDERED="$(mktemp)"
  chmod 600 "${RENDERED}"

  # The shell keeps the POSIX paths for its own rm/chmod; python and az receive
  # native equivalents.
  TEMPLATE_NATIVE="$(to_native_path "${TEMPLATE}")"
  RENDERED_NATIVE="$(to_native_path "${RENDERED}")"

  MONGODB_URI="${MONGODB_URI}" \
  LOCATION="${LOCATION}" \
  ENVIRONMENT_ID="${ENVIRONMENT_ID}" \
  IDENTITY_RESOURCE_ID="${IDENTITY_RESOURCE_ID}" \
  ACR_LOGIN_SERVER="${ACR_LOGIN_SERVER}" \
  BOOTSTRAP_IMAGE="${BOOTSTRAP_IMAGE}" \
  python - "${TEMPLATE_NATIVE}" "${RENDERED_NATIVE}" <<'PYTHON'
import io, os, sys

template, target = sys.argv[1], sys.argv[2]
text = io.open(template, encoding='utf-8').read()

for placeholder, value in {
    '__LOCATION__': os.environ['LOCATION'],
    '__ENVIRONMENT_ID__': os.environ['ENVIRONMENT_ID'],
    '__IDENTITY_RESOURCE_ID__': os.environ['IDENTITY_RESOURCE_ID'],
    '__ACR_LOGIN_SERVER__': os.environ['ACR_LOGIN_SERVER'],
    '__IMAGE__': os.environ['BOOTSTRAP_IMAGE'],
    '__MONGODB_URI__': os.environ['MONGODB_URI'],
}.items():
    text = text.replace(placeholder, value)

leftover = [line for line in text.splitlines()
            if '__' in line and not line.strip().startswith('#')]
if leftover:
    raise SystemExit('Unrendered placeholders remain: ' + '; '.join(leftover))

# Confirming substitution happened is not enough: a rewritten path substitutes
# cleanly and still yields an unusable spec. Verify the shape of what landed.
import yaml

spec = yaml.safe_load(text)
env_id = str(spec['properties']['managedEnvironmentId'])
if not env_id.startswith('/subscriptions/'):
    raise SystemExit('Rendered environment id is not a resource id: ' + env_id)
identity = str(next(iter(spec['identity']['userAssignedIdentities'])))
if not identity.startswith('/subscriptions/'):
    raise SystemExit('Rendered identity id is not a resource id: ' + identity)

io.open(target, 'w', encoding='utf-8', newline='\n').write(text)
PYTHON

  unset MONGODB_URI

  echo "    creating from the rendered spec (probes, replicas, ingress, secret)"
  az containerapp create \
    --name "${CONTAINERAPP_NAME}" \
    --resource-group "${RESOURCE_GROUP}" \
    --subscription "${SUBSCRIPTION_ID}" \
    --yaml "${RENDERED_NATIVE}" \
    --only-show-errors >/dev/null

  rm -f "${RENDERED}"
  unset RENDERED

  echo "    created. The first revision runs a placeholder image and will report"
  echo "    unhealthy until the first deployment replaces it — that is expected."
fi

APP_ID_RESOURCE="$(resource_id "Container App id" \
  az containerapp show --name "${CONTAINERAPP_NAME}" \
  --resource-group "${RESOURCE_GROUP}" --subscription "${SUBSCRIPTION_ID}" --query id -o tsv)"

# --------------------------------------------------------------------------------
# GitHub OIDC deployer
# --------------------------------------------------------------------------------

echo "==> GitHub deployer app registration"
DEPLOYER_APP_ID="$(az ad app list --display-name "${APP_REGISTRATION_NAME}" \
  --query "[0].appId" -o tsv 2>/dev/null || echo "")"

if [[ -z "${DEPLOYER_APP_ID}" ]]; then
  DEPLOYER_APP_ID="$(az ad app create --display-name "${APP_REGISTRATION_NAME}" \
    --query appId -o tsv --only-show-errors)"
  echo "    created app registration ${DEPLOYER_APP_ID}"
else
  echo "    app registration already exists (${DEPLOYER_APP_ID})"
fi

if ! az ad sp show --id "${DEPLOYER_APP_ID}" >/dev/null 2>&1; then
  az ad sp create --id "${DEPLOYER_APP_ID}" --only-show-errors >/dev/null
  echo "    created service principal"
fi

# Two subjects, because the workflow declares `environment: production`. GitHub emits
# the environment subject for those jobs and the ref subject otherwise; registering
# both means either shape authenticates. A mismatch here is the usual cause of
# AADSTS70021 on the first deploy.
add_federated_credential() {
  local name="$1" subject="$2"

  local existing
  existing="$(az ad app federated-credential list --id "${DEPLOYER_APP_ID}" \
    --query "[?name=='${name}'].subject" -o tsv 2>/dev/null || echo "")"

  if [[ -n "${existing}" ]]; then
    if [[ "${existing}" != "${subject}" ]]; then
      fail "Federated credential '${name}' already points at a DIFFERENT subject.

       existing: ${existing}
       expected: ${subject}

       Not changing it automatically: repointing which repository may deploy is a
       security decision. Remove it deliberately with:
         az ad app federated-credential delete --id ${DEPLOYER_APP_ID} --federated-credential-id ${name}"
    fi
    echo "    ${name} verified"
    return 0
  fi

  az ad app federated-credential create --id "${DEPLOYER_APP_ID}" --only-show-errors \
    --parameters "{
      \"name\": \"${name}\",
      \"issuer\": \"https://token.actions.githubusercontent.com\",
      \"subject\": \"${subject}\",
      \"audiences\": [\"api://AzureADTokenExchange\"]
    }" >/dev/null
  echo "    created ${name}"
}

echo "==> Federated credentials"
add_federated_credential "github-environment-production" \
  "repo:${GITHUB_REPOSITORY}:environment:production"
add_federated_credential "github-branch-main" \
  "repo:${GITHUB_REPOSITORY}:ref:refs/heads/main"

# --------------------------------------------------------------------------------
# Deployer permissions — narrowly scoped
# --------------------------------------------------------------------------------

echo "==> Deployer permissions"

# Push images to this registry only.
az role assignment create \
  --assignee "${DEPLOYER_APP_ID}" \
  --role AcrPush \
  --scope "${ACR_ID}" \
  --subscription "${SUBSCRIPTION_ID}" \
  --only-show-errors >/dev/null 2>&1 || true
echo "    AcrPush on ${ACR_NAME}"

# Update this one Container App — not the resource group, not the subscription.
az role assignment create \
  --assignee "${DEPLOYER_APP_ID}" \
  --role "Container Apps Contributor" \
  --scope "${APP_ID_RESOURCE}" \
  --subscription "${SUBSCRIPTION_ID}" \
  --only-show-errors >/dev/null 2>&1 || true
echo "    Container Apps Contributor on ${CONTAINERAPP_NAME}"

# --------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------

FQDN="$(az containerapp show --name "${CONTAINERAPP_NAME}" --resource-group "${RESOURCE_GROUP}" \
  --subscription "${SUBSCRIPTION_ID}" --query properties.configuration.ingress.fqdn -o tsv 2>/dev/null || echo "")"

echo
echo "Setup complete."
echo
echo "GitHub → Settings → Secrets and variables → Actions"
echo
echo "  Secrets:"
echo "    AZURE_CLIENT_ID        ${DEPLOYER_APP_ID}"
echo "    AZURE_TENANT_ID        ${TENANT_ID}"
echo "    AZURE_SUBSCRIPTION_ID  ${SUBSCRIPTION_ID}"
echo
echo "  Variables:"
echo "    ACR_NAME               ${ACR_NAME}"
echo "    AZURE_RESOURCE_GROUP   ${RESOURCE_GROUP}"
echo "    AZURE_REGION           ${LOCATION}"
echo "    CONTAINERAPP_NAME      ${CONTAINERAPP_NAME}"
echo
if [[ -n "${FQDN}" ]]; then
  echo "  Service URL (live after the first deployment):"
  echo "    https://${FQDN}"
  echo
fi
echo "Also required: a GitHub environment named 'production' must exist, since"
echo "deploy.yml declares it and one federated credential is bound to that subject."
