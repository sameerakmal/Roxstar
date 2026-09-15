#!/usr/bin/env bash
# One-time Google Cloud setup for the Roxstar backend.
#
# Creates the Artifact Registry repository, the MongoDB secret, the runtime and
# deployer service accounts, and the Workload Identity Federation binding that lets
# GitHub Actions deploy without a long-lived key.
#
# Idempotent: re-running is safe. Creation steps tolerate ALREADY_EXISTS and nothing
# else — any other gcloud failure aborts the script with the real error rather than
# being swallowed as "already exists".
#
# Every command passes --project explicitly, so the script never depends on (or
# mutates) your ambient `gcloud config` state.
#
# Usage:
#   export GITHUB_REPOSITORY="your-org/your-repo"
#   ./infrastructure/cloudrun-setup.sh
#
# The MongoDB connection string is read from stdin, never from a command-line
# argument, so it does not land in your shell history.

set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-roxstar-backend}"
REGION="${GCP_REGION:-asia-south1}"
REPOSITORY="${ARTIFACT_REPOSITORY:-roxstar}"
SERVICE_NAME="${SERVICE_NAME:-roxstar-backend}"
SECRET_NAME="${MONGODB_SECRET_NAME:-roxstar-mongodb-uri}"

RUNTIME_SA="${SERVICE_NAME}-run"
DEPLOYER_SA="${SERVICE_NAME}-deployer"
POOL="github-pool"
PROVIDER="github-provider"

ISSUER_URI="https://token.actions.githubusercontent.com"
ATTRIBUTE_MAPPING="google.subject=assertion.sub,attribute.repository=assertion.repository"

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY must be set, for example: export GITHUB_REPOSITORY=your-org/your-repo" >&2
  exit 1
fi

# The security boundary: only this repository may mint tokens for this project.
ATTRIBUTE_CONDITION="assertion.repository == '${GITHUB_REPOSITORY}'"

# --------------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------------

fail() {
  echo >&2
  echo "ERROR: $*" >&2
  exit 1
}

# Runs a gcloud create command and tolerates ONLY an already-exists outcome. Every
# other failure is reported with the real gcloud error and aborts the script, so a
# permission or quota problem can never be mistaken for an existing resource.
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

  if grep -qiE 'ALREADY_EXISTS|already exists' <<<"${output}"; then
    echo "    ${description} already exists"
    return 0
  fi

  echo "    failed to create ${description}" >&2
  echo "${output}" >&2
  fail "gcloud exited with status ${status} while creating ${description}"
}

# Normalizes whitespace and quote style so a semantically identical condition does
# not read as a mismatch.
normalize() {
  tr -d '\r' <<<"$1" | tr -s '[:space:]' ' ' | sed "s/\"/'/g; s/^ //; s/ $//"
}

# --------------------------------------------------------------------------------
# Preflight
# --------------------------------------------------------------------------------

command -v gcloud >/dev/null 2>&1 || fail "gcloud CLI not found on PATH."

if ! gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | grep -q .; then
  fail "No active gcloud account. Run: gcloud auth login"
fi

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)' 2>/dev/null)" \
  || fail "Cannot access project '${PROJECT_ID}'. Check the ID and your permissions."

echo "Project : ${PROJECT_ID} (${PROJECT_NUMBER})"
echo "Region  : ${REGION}"
echo "Repo    : ${GITHUB_REPOSITORY}"
echo

# --------------------------------------------------------------------------------
# APIs
# --------------------------------------------------------------------------------

echo "==> Enabling required APIs"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com \
  --project="${PROJECT_ID}" \
  --quiet

# --------------------------------------------------------------------------------
# Artifact Registry
# --------------------------------------------------------------------------------

echo "==> Artifact Registry repository"
create_if_absent "repository '${REPOSITORY}'" \
  gcloud artifacts repositories create "${REPOSITORY}" \
  --repository-format=docker \
  --location="${REGION}" \
  --description="Roxstar backend container images" \
  --project="${PROJECT_ID}" \
  --quiet

# --------------------------------------------------------------------------------
# Service accounts
# --------------------------------------------------------------------------------

echo "==> Service accounts"
create_if_absent "runtime service account '${RUNTIME_SA}'" \
  gcloud iam service-accounts create "${RUNTIME_SA}" \
  --display-name="Roxstar Cloud Run runtime" \
  --project="${PROJECT_ID}" \
  --quiet

create_if_absent "deployer service account '${DEPLOYER_SA}'" \
  gcloud iam service-accounts create "${DEPLOYER_SA}" \
  --display-name="Roxstar GitHub Actions deployer" \
  --project="${PROJECT_ID}" \
  --quiet

RUNTIME_EMAIL="${RUNTIME_SA}@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOYER_EMAIL="${DEPLOYER_SA}@${PROJECT_ID}.iam.gserviceaccount.com"

# --------------------------------------------------------------------------------
# MongoDB Atlas connection string
# --------------------------------------------------------------------------------

echo "==> MongoDB Atlas connection string"
if gcloud secrets describe "${SECRET_NAME}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "    secret '${SECRET_NAME}' exists; rotate with:"
  echo "    gcloud secrets versions add ${SECRET_NAME} --project=${PROJECT_ID} --data-file=-"
else
  echo "    Paste the Atlas SRV string (mongodb+srv://...), then press Ctrl-D:"
  gcloud secrets create "${SECRET_NAME}" \
    --replication-policy=automatic \
    --project="${PROJECT_ID}" \
    --data-file=-
fi

# The runtime account reads exactly this one secret and holds no other permission.
gcloud secrets add-iam-policy-binding "${SECRET_NAME}" \
  --member="serviceAccount:${RUNTIME_EMAIL}" \
  --role="roles/secretmanager.secretAccessor" \
  --project="${PROJECT_ID}" \
  --condition=None \
  --quiet >/dev/null

# --------------------------------------------------------------------------------
# Deployer permissions
# --------------------------------------------------------------------------------

echo "==> Deployer permissions"
for ROLE in roles/run.admin roles/artifactregistry.writer roles/iam.serviceAccountUser; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${DEPLOYER_EMAIL}" \
    --role="${ROLE}" \
    --condition=None \
    --quiet >/dev/null
  echo "    ${ROLE}"
done

# --------------------------------------------------------------------------------
# Workload Identity Federation
# --------------------------------------------------------------------------------

echo "==> Workload Identity Federation pool"
create_if_absent "pool '${POOL}'" \
  gcloud iam workload-identity-pools create "${POOL}" \
  --location=global \
  --display-name="GitHub Actions" \
  --project="${PROJECT_ID}" \
  --quiet

echo "==> Workload Identity Federation provider"
if gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
  --location=global \
  --workload-identity-pool="${POOL}" \
  --project="${PROJECT_ID}" >/dev/null 2>&1; then

  echo "    provider '${PROVIDER}' exists — verifying its restrictions"

  EXISTING_CONDITION="$(gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
    --location=global --workload-identity-pool="${POOL}" --project="${PROJECT_ID}" \
    --format='value(attributeCondition)')"
  EXISTING_ISSUER="$(gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
    --location=global --workload-identity-pool="${POOL}" --project="${PROJECT_ID}" \
    --format='value(oidc.issuerUri)')"
  EXISTING_MAPPING="$(gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
    --location=global --workload-identity-pool="${POOL}" --project="${PROJECT_ID}" \
    --format='value(attributeMapping)')"
  EXISTING_STATE="$(gcloud iam workload-identity-pools providers describe "${PROVIDER}" \
    --location=global --workload-identity-pool="${POOL}" --project="${PROJECT_ID}" \
    --format='value(state)')"

  # An unconditioned provider would let ANY GitHub repository on the internet mint
  # tokens for this project. Refuse to continue rather than quietly reuse it.
  if [[ -z "$(normalize "${EXISTING_CONDITION}")" ]]; then
    fail "Provider '${PROVIDER}' has NO attribute condition, so any GitHub repository could
       impersonate the deployer. Refusing to continue.

       Fix it deliberately with:
         gcloud iam workload-identity-pools providers update-oidc ${PROVIDER} \\
           --location=global --workload-identity-pool=${POOL} --project=${PROJECT_ID} \\
           --attribute-condition=\"${ATTRIBUTE_CONDITION}\""
  fi

  if [[ "$(normalize "${EXISTING_CONDITION}")" != "$(normalize "${ATTRIBUTE_CONDITION}")" ]]; then
    fail "Provider '${PROVIDER}' is restricted to a DIFFERENT repository.

       existing: $(normalize "${EXISTING_CONDITION}")
       expected: $(normalize "${ATTRIBUTE_CONDITION}")

       Not changing it automatically: widening or repointing this condition is a
       security decision. Either set GITHUB_REPOSITORY to the repository above, or
       update the provider deliberately with:
         gcloud iam workload-identity-pools providers update-oidc ${PROVIDER} \\
           --location=global --workload-identity-pool=${POOL} --project=${PROJECT_ID} \\
           --attribute-condition=\"${ATTRIBUTE_CONDITION}\""
  fi

  if [[ "${EXISTING_ISSUER}" != "${ISSUER_URI}" ]]; then
    fail "Provider '${PROVIDER}' trusts an unexpected issuer.

       existing: ${EXISTING_ISSUER}
       expected: ${ISSUER_URI}

       Refusing to continue: this provider was not created for GitHub Actions."
  fi

  # The service-account binding below is keyed on attribute.repository, so the
  # mapping must actually produce it.
  if ! grep -q 'attribute.repository' <<<"${EXISTING_MAPPING}"; then
    fail "Provider '${PROVIDER}' does not map attribute.repository.

       existing mapping: ${EXISTING_MAPPING}
       expected to include: attribute.repository=assertion.repository

       The repository-scoped binding cannot work without it."
  fi

  if [[ -n "${EXISTING_STATE}" && "${EXISTING_STATE}" != "ACTIVE" ]]; then
    fail "Provider '${PROVIDER}' is in state '${EXISTING_STATE}', not ACTIVE."
  fi

  echo "    verified: issuer, repository condition and attribute mapping all match"
else
  create_if_absent "provider '${PROVIDER}'" \
    gcloud iam workload-identity-pools providers create-oidc "${PROVIDER}" \
    --location=global \
    --workload-identity-pool="${POOL}" \
    --display-name="GitHub OIDC" \
    --issuer-uri="${ISSUER_URI}" \
    --attribute-mapping="${ATTRIBUTE_MAPPING}" \
    --attribute-condition="${ATTRIBUTE_CONDITION}" \
    --project="${PROJECT_ID}" \
    --quiet
fi

POOL_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}"

# Scoped to this repository only, never to the whole pool.
gcloud iam service-accounts add-iam-policy-binding "${DEPLOYER_EMAIL}" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/${POOL_RESOURCE}/attribute.repository/${GITHUB_REPOSITORY}" \
  --project="${PROJECT_ID}" \
  --condition=None \
  --quiet >/dev/null

# --------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------

echo
echo "Setup complete. Add these to GitHub → Settings → Secrets and variables → Actions:"
echo
echo "  Secret  GCP_WORKLOAD_IDENTITY_PROVIDER"
echo "          ${POOL_RESOURCE}/providers/${PROVIDER}"
echo
echo "  Secret  GCP_DEPLOYER_SERVICE_ACCOUNT"
echo "          ${DEPLOYER_EMAIL}"
echo
echo "  Variable GCP_PROJECT_ID = ${PROJECT_ID}   (optional, this is the default)"
echo "  Variable GCP_REGION     = ${REGION}       (optional, this is the default)"
echo
echo "The Cloud Run service must run as ${RUNTIME_EMAIL} to read the secret:"
echo "  gcloud run services update ${SERVICE_NAME} \\"
echo "    --region ${REGION} --project ${PROJECT_ID} \\"
echo "    --service-account ${RUNTIME_EMAIL}"
