"""Run-owned progress updates using the existing deletion guard and transaction client."""
from datetime import datetime, timezone
from decimal import Decimal
import re


class SupersededTrainingRun(RuntimeError):
    """The row belongs to a later/different run; stop without claiming its progress."""


SNAPSHOT_FIELDS = ('status', 'isActive', 'deletionStatus', 'trainingStatus',
                   'trainingStatusRunId', 'trainingStatusRunStartedAtNs')


def validate_run_id(run_id):
    if (not isinstance(run_id, str) or not run_id or run_id.strip() != run_id
            or '/' in run_id or '\\' in run_id or '..' in run_id):
        raise ValueError('A nonempty, unique training run ID without path separators is required')
    return run_id


def validate_run(run_id, started_at):
    """Use execution start time, never container start/retry time, to order runs."""
    validate_run_id(run_id)
    match = re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z', str(started_at))
    if not match:
        raise ValueError('TRAINING_RUN_STARTED_AT must be the ISO UTC Step Functions execution start time')
    instant = datetime.strptime(match[1], '%Y-%m-%dT%H:%M:%S').replace(tzinfo=timezone.utc)
    elapsed = instant - datetime(1970, 1, 1, tzinfo=timezone.utc)
    nanos = (elapsed.days * 86400 + elapsed.seconds) * 1_000_000_000
    nanos += int((match[2] or '').ljust(9, '0'))
    if nanos <= 0:
        raise ValueError('Invalid execution start time')
    return run_id, nanos


def _owner(row):
    owner = row.get('trainingStatusRunId')
    timestamp = row.get('trainingStatusRunStartedAtNs')
    if 'trainingStatusRunId' not in row and 'trainingStatusRunStartedAtNs' not in row:
        return None, None  # Legacy progress can be claimed by a new execution.
    validate_run_id(owner)
    if (isinstance(timestamp, bool) or not isinstance(timestamp, (int, Decimal))
            or int(timestamp) != timestamp or timestamp <= 0):
        raise ValueError('Existing progress has incomplete/invalid run ownership; refusing overwrite')
    return owner, int(timestamp)


def _commit(guard, landmark_id, observed, updates):
    """Both deletion absence and exact observed row fields must hold at commit."""
    names, values, conditions, assignments = {}, {}, ['attribute_exists(landmarkId)'], []
    for index, field in enumerate(SNAPSHOT_FIELDS):
        name, value = '#p' + str(index), ':p' + str(index)
        names[name] = field
        if field in observed:
            conditions.append(name + ' = ' + value)
            values[value] = observed[field]
        else:
            conditions.append('attribute_not_exists(' + name + ')')
    for index, (field, data) in enumerate(updates.items()):
        name, value = '#u' + str(index), ':u' + str(index)
        names[name] = field
        values[value] = data
        assignments.append(name + ' = ' + value)
    # Preserve the exact marker transaction used by the reviewed DeletionGuard.
    guard.db.meta.client.transact_write_items(TransactItems=[
        {'ConditionCheck': {'TableName': guard.marker_name, 'Key': {'landmarkId': landmark_id},
                            'ConditionExpression': 'attribute_not_exists(landmarkId)'}},
        {'Update': {'TableName': guard.main_name, 'Key': {'landmarkId': landmark_id},
                    'UpdateExpression': 'SET ' + ', '.join(assignments),
                    'ConditionExpression': ' AND '.join(conditions),
                    'ExpressionAttributeNames': names, 'ExpressionAttributeValues': values}},
    ])


def packaging_progress(guard, landmark_id, status, run_id, started_at, metrics):
    """Claim newer progress without regressing ACTIVE availability or a later stage."""
    run_id, timestamp = validate_run(run_id, started_at)
    if status not in ('TRAINING', 'NEEDS_MORE_MEDIA'):
        raise ValueError('Unsupported packaging progress')
    allowed_metrics = {'cleanFrameCount', 'requiredFrames', 'secondsNeeded'}
    if not isinstance(metrics, dict) or not set(metrics) <= allowed_metrics:
        raise ValueError('Unsupported progress metrics')
    row = guard.check(landmark_id)  # Existing missing/deletion guards still run.
    old_run, old_time = _owner(row)
    if old_run == run_id:
        if timestamp != old_time:
            raise ValueError('Run ID reused with a different execution start time')
        if row.get('trainingStatus') in ('TRAINING_MODEL', 'COMPLETE'):
            return False
    elif old_run is not None and timestamp <= old_time:
        raise SupersededTrainingRun('Newer or equally-timed run owns progress for ' + landmark_id)
    updates = dict(metrics)
    updates.update(trainingStatus=status, trainingStatusRunId=run_id,
                   trainingStatusRunStartedAtNs=timestamp)
    if row.get('status') != 'ACTIVE':
        updates['status'] = status
    _commit(guard, landmark_id, row, updates)
    return True


def training_progress(guard, landmark_id, run_id):
    """A trainer may advance only progress already claimed by its packager."""
    validate_run_id(run_id)
    row = guard.check(landmark_id)
    owner, timestamp = _owner(row)
    if owner != run_id:
        raise SupersededTrainingRun('Trainer no longer owns progress for ' + landmark_id)
    if row.get('trainingStatus') == 'COMPLETE':
        return False
    if row.get('trainingStatus') not in ('TRAINING', 'TRAINING_MODEL'):
        raise RuntimeError('Training requires progress owned by an eligible packaging result')
    _commit(guard, landmark_id, row, {'trainingStatus': 'TRAINING_MODEL',
                                    'trainingStatusRunId': run_id})
    return True
