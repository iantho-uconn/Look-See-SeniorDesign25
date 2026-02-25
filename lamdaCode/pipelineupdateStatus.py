import json, os, time
import boto3

ddb = boto3.client("dynamodb")
TABLE = os.environ["TABLE_NAME"]

def pipelineupdateStatus_handler(event, context):
    # expects: {"submissionId": "...", "status": "...", "statusReason": "...optional...", "extra": {...optional...}}
    submission_id = event["submissionId"]
    status = event["status"]
    reason = event.get("statusReason", "")
    now = int(time.time())

    update_expr = "SET #s = :s, pipelineUpdatedAt = :t"
    names = {"#s": "status"}
    values = {":s": {"S": status}, ":t": {"N": str(now)}}

    if reason:
        update_expr += ", statusReason = :r"
        values[":r"] = {"S": reason}

    ddb.update_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}},
        UpdateExpression=update_expr,
        ExpressionAttributeNames=names,
        ExpressionAttributeValues=values,
    )

    return {"ok": True, "submissionId": submission_id, "status": status}