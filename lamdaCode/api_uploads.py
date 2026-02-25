import json, os, uuid, time
import boto3

s3 = boto3.client("s3")
ddb = boto3.client("dynamodb")

BUCKET = os.environ["UPLOAD_BUCKET"]
TABLE = os.environ["TABLE_NAME"]
PREFIX = os.environ.get("UPLOAD_PREFIX", "uploads/raw")

def _resp(code, body):
    return {
        "statusCode": code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }

def api_uploads_handler(event, context):
    path = (event.get("rawPath") or event.get("path") or "").lower()
    method = (event.get("requestContext", {}).get("http", {}).get("method")
              or event.get("httpMethod") or "").upper()

    if path.endswith("/submissions/init") and method == "POST":
        return handle_init(event)

    if path.endswith("/submissions/complete") and method == "POST":
        return handle_complete(event)

    return _resp(404, {"error": "Not found", "path": path, "method": method})

def handle_init(event):
    try:
        body = json.loads(event.get("body") or "{}")
        label = body["label"].strip()
        media_kind = body["mediaKind"]  # "video" or "photo"
        filename = body["filename"]
        content_type = body.get("contentType", "application/octet-stream")
        if not label:
            return _resp(400, {"error": "label required"})
        if media_kind not in ("video", "photo"):
            return _resp(400, {"error": "mediaKind must be 'video' or 'photo'"})
    except Exception as e:
        return _resp(400, {"error": "bad request", "detail": str(e)})

    submission_id = str(uuid.uuid4())
    created_at = int(time.time())

    # MVP: no auth yet
    user_id = "anonymous"

    # Separate paths by kind
    kind_prefix = "videos" if media_kind == "video" else "images"
    s3_key = f"{PREFIX}/{kind_prefix}/{user_id}/{submission_id}/{filename}"

    presigned = s3.generate_presigned_url(
        ClientMethod="put_object",
        Params={
            "Bucket": BUCKET,
            "Key": s3_key,
            "ContentType": content_type,
        },
        ExpiresIn=900,  # 15 minutes
    )

    ddb.put_item(
        TableName=TABLE,
        Item={
            "submissionId": {"S": submission_id},
            "label": {"S": label},
            "mediaKind": {"S": media_kind},
            "s3Key": {"S": s3_key},
            "status": {"S": "INITIATED"},
            "createdAt": {"N": str(created_at)},
        },
    )

    return _resp(200, {
        "submissionId": submission_id,
        "uploadUrl": presigned,
        "s3Key": s3_key
    })

def handle_complete(event):
    try:
        body = json.loads(event.get("body") or "{}")
        submission_id = body["submissionId"]
        s3_key = body["s3Key"]
    except Exception as e:
        return _resp(400, {"error": "bad request", "detail": str(e)})

    ddb.update_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}},
        UpdateExpression="SET #s = :s, s3Key = :k",
        ExpressionAttributeNames={"#s": "status"},
        ExpressionAttributeValues={
            ":s": {"S": "COMPLETE"},
            ":k": {"S": s3_key},
        },
    )

    return _resp(200, {"ok": True, "submissionId": submission_id})
