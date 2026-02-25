import os, json, time
import boto3
from botocore.exceptions import ClientError

ddb = boto3.client("dynamodb")
sfn = boto3.client("stepfunctions")

TABLE = os.environ["TABLE_NAME"]
SM_ARN = os.environ["STATE_MACHINE_ARN"]

def parse_submission_id(key: str) -> str | None:
    # expects: uploads/raw/{images|videos}/{userId}/{submissionId}/...
    parts = key.split("/")
    try:
        # ["uploads","raw","images","anonymous","<submissionId>","photo.jpg"]
        return parts[4]
    except Exception:
        return None

def media_kind_from_key(key: str) -> str:
    if "/videos/" in key:
        return "video"
    return "photo"

def digest_lambda_handler(event, context):
    # S3 event
    rec = event["Records"][0]
    bucket = rec["s3"]["bucket"]["name"]
    key = rec["s3"]["object"]["key"]

    submission_id = parse_submission_id(key)
    if not submission_id:
        print("Skip: could not parse submissionId from key:", key)
        return {"skipped": True, "reason": "bad_key", "key": key}

    # Fetch record from DynamoDB
    item = ddb.get_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}}
    ).get("Item")

    if not item:
        print("Skip: no DynamoDB record for:", submission_id)
        return {"skipped": True, "reason": "no_ddb_record", "submissionId": submission_id}

    status = item.get("status", {}).get("S", "")
    media_kind = item.get("mediaKind", {}).get("S", media_kind_from_key(key))

    # Always update ingestedAt
    now = int(time.time())
    ddb.update_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}},
        UpdateExpression="SET ingestedAt = :t",
        ExpressionAttributeValues={":t": {"N": str(now)}}
    )

    # Only start pipeline if COMPLETE (prevents partial uploads)
    if status != "COMPLETE":
        print(f"Skip pipeline start: status={status} submissionId={submission_id}")
        return {"skipped": True, "reason": "not_complete", "status": status, "submissionId": submission_id}

    # If pipeline already started, don’t start again
    if "pipelineExecutionArn" in item:
        existing = item["pipelineExecutionArn"].get("S", "")
        print("Skip: pipeline already started:", existing)
        return {"skipped": True, "reason": "already_started", "submissionId": submission_id, "executionArn": existing}

    # Start Step Functions
    inp = {
        "submissionId": submission_id,
        "bucket": bucket,
        "s3Key": key,
        "mediaKind": media_kind
    }

    resp = sfn.start_execution(
        stateMachineArn=SM_ARN,
        input=json.dumps(inp)
    )
    execution_arn = resp["executionArn"]

    # Store pipelineExecutionArn (idempotent)
    try:
        ddb.update_item(
            TableName=TABLE,
            Key={"submissionId": {"S": submission_id}},
            UpdateExpression="SET pipelineExecutionArn = :a",
            ExpressionAttributeValues={":a": {"S": execution_arn}},
            ConditionExpression="attribute_not_exists(pipelineExecutionArn)"
        )
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            print("Skip: another trigger already wrote pipelineExecutionArn")
            return {"skipped": True, "reason": "already_started_race", "submissionId": submission_id}
        raise

    print("Started pipeline:", execution_arn)
    return {"started": True, "executionArn": execution_arn, "submissionId": submission_id}