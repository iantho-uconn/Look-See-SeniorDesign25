#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
TABLE_NAME="${PUBLISHED_CLUSTER_MAPPINGS_TABLE:-LookSeePublishedClusterMappings}"

aws dynamodb create-table \
  --region "$AWS_REGION" \
  --table-name "$TABLE_NAME" \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=mappingVersion,AttributeType=S \
    AttributeName=landmarkId,AttributeType=S \
  --key-schema \
    AttributeName=mappingVersion,KeyType=HASH \
    AttributeName=landmarkId,KeyType=RANGE \
  --tags \
    Key=Project,Value=LookSee \
    Key=Purpose,Value=PublishedClusterMappings

aws dynamodb wait table-exists \
  --region "$AWS_REGION" \
  --table-name "$TABLE_NAME"

aws dynamodb update-continuous-backups \
  --region "$AWS_REGION" \
  --table-name "$TABLE_NAME" \
  --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true

echo "Created $TABLE_NAME in $AWS_REGION with point-in-time recovery enabled."
