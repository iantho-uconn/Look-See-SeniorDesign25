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

        label = (body.get("label") or "").strip()
        landmark_id = (body.get("landmarkId") or "").strip()
        landmark_label = (body.get("landmarkLabel") or "").strip()

        media_kind = body["mediaKind"]  # "video" or "photo"
        filename = body["filename"]
        content_type = body.get("contentType", "application/octet-stream")

        if not label and not landmark_id:
            return _resp(400, {"error": "label or landmarkId required"})

        if media_kind not in ("video", "photo"):
            return _resp(400, {"error": "mediaKind must be 'video' or 'photo'"})

    except Exception as e:
        return _resp(400, {"error": "bad request", "detail": str(e)})

    submission_id = str(uuid.uuid4())
    created_at = int(time.time())

    # MVP: no auth yet
    user_id = "anonymous"

    kind_prefix = "videos" if media_kind == "video" else "images"
    s3_key = f"{PREFIX}/{kind_prefix}/{user_id}/{submission_id}/{filename}"

    presigned = s3.generate_presigned_url(
        ClientMethod="put_object",
        Params={
            "Bucket": BUCKET,
            "Key": s3_key,
            "ContentType": content_type,
        },
        ExpiresIn=900,
    )

    item = {
        "submissionId": {"S": submission_id},
        "mediaKind": {"S": media_kind},
        "s3Key": {"S": s3_key},
        "status": {"S": "INITIATED"},
        "createdAt": {"N": str(created_at)},
    }

    if label:
        item["label"] = {"S": label}

    if landmark_id:
        item["landmarkId"] = {"S": landmark_id}

    if landmark_label:
        item["landmarkLabel"] = {"S": landmark_label}

    ddb.put_item(
        TableName=TABLE,
        Item=item,
    )

    return _resp(200, {
        "submissionId": submission_id,
        "uploadUrl": presigned,
        "s3Key": s3_key
    })

def _s(val):
    if val is None:
        return None
    if isinstance(val, str):
        v = val.strip()
        return {"S": v} if v else None
    return {"S": str(val)}

def _n(val):
    if val is None:
        return None
    try:
        return {"N": str(float(val))}
    except Exception:
        return None

def handle_complete(event):
    try:
        body = json.loads(event.get("body") or "{}")

        submission_id = body["submissionId"]
        s3_key = body["s3Key"]

        label = body.get("label")
        landmark_id = body.get("landmarkId")
        landmark_label = body.get("landmarkLabel")

        media_kind = body.get("mediaKind")
        short_desc = body.get("shortDescription")
        user_desc = body.get("userDescription")

        lat = body.get("latitude")
        lon = body.get("longitude")
        acc = body.get("horizontalAccuracy")

    except Exception as e:
        return _resp(400, {"error": "bad request", "detail": str(e)})

    update_expr_parts = ["SET #s = :s", "s3Key = :k"]
    expr_names = {"#s": "status"}
    expr_values = {
        ":s": {"S": "COMPLETE"},
        ":k": {"S": s3_key},
    }

    label_av = _s(label)
    if label_av:
        update_expr_parts.append("label = :label")
        expr_values[":label"] = label_av

    landmark_id_av = _s(landmark_id)
    if landmark_id_av:
        update_expr_parts.append("landmarkId = :lid")
        expr_values[":lid"] = landmark_id_av

    landmark_label_av = _s(landmark_label)
    if landmark_label_av:
        update_expr_parts.append("landmarkLabel = :llabel")
        expr_values[":llabel"] = landmark_label_av

    media_av = _s(media_kind)
    if media_av:
        update_expr_parts.append("mediaKind = :mk")
        expr_values[":mk"] = media_av

    short_av = _s(short_desc)
    if short_av:
        update_expr_parts.append("shortDescription = :sd")
        expr_values[":sd"] = short_av

    user_av = _s(user_desc)
    if user_av:
        update_expr_parts.append("userDescription = :ud")
        expr_values[":ud"] = user_av

    lat_av = _n(lat)
    if lat_av:
        update_expr_parts.append("latitude = :lat")
        expr_values[":lat"] = lat_av

    lon_av = _n(lon)
    if lon_av:
        update_expr_parts.append("longitude = :lon")
        expr_values[":lon"] = lon_av

    acc_av = _n(acc)
    if acc_av:
        update_expr_parts.append("horizontalAccuracy = :acc")
        expr_values[":acc"] = acc_av

    now = int(time.time())
    update_expr_parts.append("completedAt = :t")
    expr_values[":t"] = {"N": str(now)}

    update_expr = ", ".join(update_expr_parts)

    ddb.update_item(
        TableName=TABLE,
        Key={"submissionId": {"S": submission_id}},
        UpdateExpression=update_expr,
        ExpressionAttributeNames=expr_names,
        ExpressionAttributeValues=expr_values,
    )

    return _resp(200, {"ok": True, "submissionId": submission_id})