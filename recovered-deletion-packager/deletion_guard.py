"""Fail closed on deletion or unavailable eligibility data. No data is deleted."""
import os
import time
import boto3

class DeletionBlocked(RuntimeError):
    pass

class DeletionGuard:
    def __init__(self):
        self.db = boto3.resource('dynamodb', region_name=os.environ.get('AWS_REGION', 'us-east-1'))
        self.main_name = os.environ.get('LANDMARKS_TABLE', 'LookSeeLandmarks')
        self.marker_name = os.environ.get('DELETIONS_TABLE', 'LookSeeLandmarkDeletions')
        self.last_check = 0
        self.ids = set()

    def check(self, landmark_id):
        if not isinstance(landmark_id, str) or not landmark_id.strip():
            raise ValueError('Missing landmarkId; refusing unbound training data')
        marker = self.db.Table(self.marker_name).get_item(Key={'landmarkId': landmark_id}, ConsistentRead=True).get('Item')
        if marker:
            raise DeletionBlocked(f'deletion_requested: {landmark_id}')
        item = self.db.Table(self.main_name).get_item(Key={'landmarkId': landmark_id}, ConsistentRead=True).get('Item')
        if not item:
            raise DeletionBlocked(f'landmark_missing: {landmark_id}')
        if 'deletionStatus' in item or item.get('status') in ('DELETING', 'DELETED'):
            raise DeletionBlocked(f'deletion_requested: {landmark_id}')
        return item

    def check_all(self, force=True):
        if not force and time.monotonic() - self.last_check < 5:
            return
        for landmark_id in sorted(self.ids):
            self.check(landmark_id)
        self.last_check = time.monotonic()

    def update(self, landmark_id, expression, names, values):
        self.check(landmark_id)
        names = dict(names, **{'#guardStatus': 'status'})
        values = dict(values, **{':guardDeleting': 'DELETING', ':guardDeleted': 'DELETED'})
        # The resource client's transaction serializer accepts native Python values.
        self.db.meta.client.transact_write_items(TransactItems=[
            {'ConditionCheck': {'TableName': self.marker_name, 'Key': {'landmarkId': landmark_id},
                'ConditionExpression': 'attribute_not_exists(landmarkId)'}},
            {'Update': {'TableName': self.main_name, 'Key': {'landmarkId': landmark_id},
                'UpdateExpression': expression, 'ExpressionAttributeNames': names,
                'ExpressionAttributeValues': values,
                'ConditionExpression': 'attribute_exists(landmarkId) AND attribute_not_exists(deletionStatus) AND (attribute_not_exists(#guardStatus) OR (#guardStatus <> :guardDeleting AND #guardStatus <> :guardDeleted))'}}
        ])
