# LookSee Published Cluster Mappings — Phase 1

This package begins the migration from live cluster mappings to mappings that
are pinned to a completed model release.

## Why the state protocol is included

The current model sender reads `LookSeeClusterMappings` directly and then
selects the newest valid S3 release for each cluster. Those two pieces of state
can represent different training generations.

A DynamoDB `Scan` is also not a transactionally isolated table snapshot, even
when `ConsistentRead=True`. For that reason, this implementation adds a small
control item to the working table:

```json
{
  "landmarkId": "__CLUSTER_STATE__",
  "status": "READY",
  "revision": "uuid"
}
```

The clustering writer marks it `UPDATING` before modifying mappings and returns
it to `READY` with a new revision only after every mapping write succeeds. The
snapshot Lambda verifies the same READY revision before and after scanning.

## Published table

Table: `LookSeePublishedClusterMappings`

Keys:

- Partition key: `mappingVersion` (String)
- Sort key: `landmarkId` (String)

Every training run receives one `mappingVersion`. The simplest rule is:

```text
mappingVersion = trainingRunId
```

A partition contains:

- One metadata record with `landmarkId=__METADATA__`
- One record per landmark mapping

## Deployment order

### 1. Create the published table

```bash
chmod +x create_published_mappings_table.sh
./create_published_mappings_table.sh
```

### 2. Initialize the working table state

Run this only once:

```bash
chmod +x initialize_working_state.sh
./initialize_working_state.sh
```

If the control item already exists, the conditional write prevents replacement.

### 3. Update the clustering Lambda/job

Merge `cluster_mapping_state.py` into the code that writes
`LookSeeClusterMappings`.

Skeleton:

```python
revision = begin_mapping_update()

try:
    # Existing logic that writes every landmark -> cluster mapping.
    rewrite_cluster_mappings(...)
    complete_mapping_update(revision)
except Exception as exc:
    fail_mapping_update(revision, exc)
    raise
```

The writer must call `begin_mapping_update()` before its first mapping change.

### 4. Create the snapshot Lambda

Use `snapshot_cluster_mappings.py` as the Lambda code.

Handler:

```text
snapshot_cluster_mappings.lambda_handler
```

Environment:

```text
CLUSTER_MAPPINGS_TABLE=LookSeeClusterMappings
PUBLISHED_CLUSTER_MAPPINGS_TABLE=LookSeePublishedClusterMappings
CLUSTER_STATE_LANDMARK_ID=__CLUSTER_STATE__
```

Attach the permissions in `snapshot_lambda_iam_policy.json` after replacing
`YOUR_ACCOUNT_ID`.

### 5. Insert it into Step Functions

Place `SnapshotClusterMappings` immediately after `PrepareRun` and before any
dataset construction or training state.

Merge the state from `step_functions_snapshot_state.json` and replace:

- `YOUR_SNAPSHOT_LAMBDA_ARN`
- `YOUR_EXISTING_DATASET_BUILD_STATE`

Downstream states should use:

```text
$.mappingSnapshot.mappingVersion
```

and include it in generated manifests and `release.json`.

### 6. Update the model sender after checking LookSeeModelVersions

`model_sender_phase1_patch.py` contains the required lookup changes. Before
deploying that patch, confirm the primary-key name and current item structure
of `LookSeeModelVersions`.

The model sender must do both of these:

1. Resolve landmark clusters from the active `mappingVersion`.
2. Select S3 releases whose `modelVersion` exactly equals the active model
   version.

It must no longer independently choose the newest release for each cluster.

## Initial migration

Do not point production at the new table until an initial snapshot and matching
active model pointer exist.

For the first snapshot, use the training run/model version associated with the
models currently considered active. If that exact mapping can no longer be
reconstructed, keep the old model sender in service, run one new full training
session, and activate the new sender only after that session succeeds.

## Files

- `create_published_mappings_table.sh`
- `initialize_working_state.sh`
- `cluster_mapping_state.py`
- `snapshot_cluster_mappings.py`
- `snapshot_lambda_iam_policy.json`
- `step_functions_snapshot_state.json`
- `model_sender_phase1_patch.py`
