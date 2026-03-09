import json
import math
import os
import boto3
from decimal import Decimal

ddb = boto3.client("dynamodb")
LANDMARKS_TABLE = os.environ["LANDMARKS_TABLE"]


def _resp(status_code, body):
    return {
        "statusCode": status_code,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*"
        },
        "body": json.dumps(body)
    }


def _to_float(attr):
    if not attr:
        return None
    if "N" in attr:
        return float(attr["N"])
    return None


def _to_string(attr):
    if not attr:
        return None
    if "S" in attr:
        return attr["S"]
    return None


def _to_bool(attr):
    if not attr:
        return False
    if "BOOL" in attr:
        return attr["BOOL"]
    return False


def haversine_meters(lat1, lon1, lat2, lon2):
    r = 6371000.0  # Earth radius in meters

    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    return r * c


def landmarks_read_handler(event, context):
    try:
        method = (
            event.get("requestContext", {}).get("http", {}).get("method")
            or event.get("httpMethod", "")
        ).upper()

        if method == "OPTIONS":
            return {
                "statusCode": 200,
                "headers": {
                    "Access-Control-Allow-Origin": "*",
                    "Access-Control-Allow-Headers": "Content-Type",
                    "Access-Control-Allow-Methods": "POST,OPTIONS"
                },
                "body": ""
            }

        if method != "POST":
            return _resp(405, {"error": "method not allowed"})

        body = json.loads(event.get("body") or "{}")

        latitude = float(body["latitude"])
        longitude = float(body["longitude"])
        radius_meters = float(body.get("radiusMeters", 100))

    except Exception as e:
        return _resp(400, {"error": "bad request", "detail": str(e)})

    try:
        scan_resp = ddb.scan(TableName=LANDMARKS_TABLE)
        items = scan_resp.get("Items", [])

        nearby = []

        for item in items:
            if not _to_bool(item.get("isActive")):
                continue

            landmark_id = _to_string(item.get("landmarkId"))
            label = _to_string(item.get("label"))
            short_description = _to_string(item.get("shortDescription"))
            lat = _to_float(item.get("latitude"))
            lon = _to_float(item.get("longitude"))

            if not landmark_id or not label or not short_description:
                continue
            if lat is None or lon is None:
                continue

            distance = haversine_meters(latitude, longitude, lat, lon)

            if distance <= radius_meters:
                nearby.append({
                    "landmarkId": landmark_id,
                    "label": label,
                    "shortDescription": short_description,
                    "latitude": lat,
                    "longitude": lon,
                    "distanceMeters": round(distance, 1)
                })

        nearby.sort(key=lambda x: x["distanceMeters"])

        return _resp(200, {
            "items": nearby,
            "count": len(nearby),
            "radiusMeters": radius_meters
        })

    except Exception as e:
        return _resp(500, {"error": "server error", "detail": str(e)})