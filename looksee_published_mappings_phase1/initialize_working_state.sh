#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
TABLE_NAME="${CLUSTER_MAPPINGS_TABLE:-LookSeeClusterMappings}"
INITIAL_REVISION="${INITIAL_REVISION:-legacy-initial}"

aws dynamodb put-item \
  --region "$AWS_REGION" \
  --table-name "$TABLE_NAME" \
  --item "{
    \"landmarkId\": {\"S\": \"__CLUSTER_STATE__\"},
    \"status\": {\"S\": \"READY\"},
    \"revision\": {\"S\": \"$INITIAL_REVISION\"}
  }" \
  --condition-expression "attribute_not_exists(landmarkId)"

echo "Initialized __CLUSTER_STATE__ in $TABLE_NAME."
