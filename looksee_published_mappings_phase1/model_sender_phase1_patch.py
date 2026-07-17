"""
Drop-in pieces for the existing LookSee model-sender Lambda.

Do not deploy this file by itself. Merge these pieces into the current
model-sender after confirming the primary-key name used by
LookSeeModelVersions.

Required active deployment item fields:
{
  "<your primary key>": "<your active item value>",
  "status": "ACTIVE",
  "mappingVersion": "<published mapping version>",
  "modelVersion": "<exact training/model release version>"
}

New environment variables:
- PUBLISHED_CLUSTER_MAPPINGS_TABLE=LookSeePublishedClusterMappings
- MODEL_VERSIONS_TABLE=LookSeeModelVersions
- ACTIVE_DEPLOYMENT_KEY_NAME=modelVersion
- ACTIVE_DEPLOYMENT_KEY_VALUE=ACTIVE
"""

from __future__ import annotations

import os
from typing import Any

import boto3


AWS_REGION = os.environ.get("AWS_REGION", "us-east-1")
dynamodb = boto3.resource("dynamodb", region_name=AWS_REGION)

published_mapping_table = dynamodb.Table(
    os.environ.get(
        "PUBLISHED_CLUSTER_MAPPINGS_TABLE",
        "LookSeePublishedClusterMappings",
    )
)

model_versions_table = dynamodb.Table(
    os.environ.get(
        "MODEL_VERSIONS_TABLE",
        "LookSeeModelVersions",
    )
)

ACTIVE_DEPLOYMENT_KEY_NAME = os.environ.get(
    "ACTIVE_DEPLOYMENT_KEY_NAME",
    "modelVersion",
)
ACTIVE_DEPLOYMENT_KEY_VALUE = os.environ.get(
    "ACTIVE_DEPLOYMENT_KEY_VALUE",
    "ACTIVE",
)


def require_nonempty_string(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(f"{field_name} is missing from active deployment")
    return value.strip()


def get_active_deployment() -> dict[str, str]:
    response = model_versions_table.get_item(
        Key={
            ACTIVE_DEPLOYMENT_KEY_NAME:
                ACTIVE_DEPLOYMENT_KEY_VALUE
        },
        ConsistentRead=True,
    )

    item = response.get("Item")
    if not item:
        raise RuntimeError("No active model deployment item exists")

    if item.get("status") != "ACTIVE":
        raise RuntimeError(
            f"Deployment pointer is not ACTIVE: {item.get('status')!r}"
        )

    return {
        "mappingVersion": require_nonempty_string(
            item.get("mappingVersion"),
            "mappingVersion",
        ),
        "modelVersion": require_nonempty_string(
            item.get("modelVersion")
            if ACTIVE_DEPLOYMENT_KEY_NAME != "modelVersion"
            else item.get("activeModelVersion"),
            "modelVersion/activeModelVersion",
        ),
    }


def get_published_cluster_mapping_for_landmark(
    landmark_id: str,
    mapping_version: str,
) -> str | None:
    response = published_mapping_table.get_item(
        Key={
            "mappingVersion": mapping_version,
            "landmarkId": landmark_id,
        },
        ConsistentRead=True,
    )

    item = response.get("Item")
    if not item or item.get("recordType") != "LANDMARK_MAPPING":
        return None

    cluster_id = item.get("clusterId")
    return str(cluster_id).strip() if cluster_id is not None else None


# In model_sender_handler(), load this once near the beginning:
#
# active_deployment = get_active_deployment()
# active_mapping_version = active_deployment["mappingVersion"]
# active_model_version = active_deployment["modelVersion"]
#
# Then replace:
#
# get_cluster_mapping_for_landmark(str(landmark_id))
#
# with:
#
# get_published_cluster_mapping_for_landmark(
#     str(landmark_id),
#     active_mapping_version,
# )
#
# Finally, replace "find latest release" behavior with exact release matching.
# The existing find_latest_complete_release() loop can be retained, but add:
#
# if model_version != active_model_version:
#     continue
#
# and rename it to:
#
# find_complete_release(
#     cluster_id: str,
#     required_model_version: str,
# )
#
# This is essential: published mappings and model releases must be selected
# using the same active deployment pointer.
