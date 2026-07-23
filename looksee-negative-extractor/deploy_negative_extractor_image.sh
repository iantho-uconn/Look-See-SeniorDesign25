#!/usr/bin/env bash
set -euo pipefail

# Run this from the existing LookSee-NegativeExtractor image project directory,
# where the Dockerfile and updated Lambda source are located.

FUNCTION_NAME="${FUNCTION_NAME:-LookSee-NegativeExtractor}"
REGION="${AWS_REGION:-us-east-1}"
TAG="${IMAGE_TAG:-history-thumbnails-$(date -u +%Y%m%dT%H%M%SZ)}"

CURRENT_IMAGE_URI="$(
  aws lambda get-function \
    --function-name "$FUNCTION_NAME" \
    --region "$REGION" \
    --query 'Code.ImageUri' \
    --output text
)"

if [[ -z "$CURRENT_IMAGE_URI" || "$CURRENT_IMAGE_URI" == "None" ]]; then
  echo "Could not determine the current image URI for $FUNCTION_NAME." >&2
  exit 1
fi

# Remove an image digest when present, then remove the old tag.
IMAGE_WITHOUT_DIGEST="${CURRENT_IMAGE_URI%@*}"
REPOSITORY_URI="${IMAGE_WITHOUT_DIGEST%:*}"
REGISTRY_URI="${REPOSITORY_URI%%/*}"

ARCHITECTURE="$(
  aws lambda get-function-configuration \
    --function-name "$FUNCTION_NAME" \
    --region "$REGION" \
    --query 'Architectures[0]' \
    --output text
)"

case "$ARCHITECTURE" in
  arm64)
    PLATFORM="linux/arm64"
    ;;
  x86_64|None|"")
    PLATFORM="linux/amd64"
    ;;
  *)
    echo "Unsupported Lambda architecture: $ARCHITECTURE" >&2
    exit 1
    ;;
esac

NEW_IMAGE_URI="${REPOSITORY_URI}:${TAG}"

echo "Function:      $FUNCTION_NAME"
echo "Architecture:  $ARCHITECTURE"
echo "Platform:      $PLATFORM"
echo "Repository:    $REPOSITORY_URI"
echo "New image:     $NEW_IMAGE_URI"

aws ecr get-login-password --region "$REGION" \
  | docker login \
      --username AWS \
      --password-stdin "$REGISTRY_URI"

docker buildx build \
  --platform "$PLATFORM" \
  --provenance=false \
  --tag "$NEW_IMAGE_URI" \
  --load \
  .

docker push "$NEW_IMAGE_URI"

aws lambda update-function-code \
  --function-name "$FUNCTION_NAME" \
  --region "$REGION" \
  --image-uri "$NEW_IMAGE_URI" \
  >/dev/null

aws lambda wait function-updated \
  --function-name "$FUNCTION_NAME" \
  --region "$REGION"

echo "Deployment complete: $NEW_IMAGE_URI"
