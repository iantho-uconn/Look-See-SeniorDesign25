import os, json, time
import boto3

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
    # key contains uploads/raw/images/... or uploads/raw/videos/...
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

    # Update ingestedAt (always)
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

    # store pipelineExecutionArn for easy debugging
    ddb.update_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}},
        UpdateExpression="SET pipelineExecutionArn = :a",
        ExpressionAttributeValues={":a": {"S": resp["executionArn"]}}
    )

    print("Started pipeline:", resp["executionArn"])
    return {"started": True, "executionArn": resp["executionArn"], "submissionId": submission_id}