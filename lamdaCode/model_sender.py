import boto3
import math
import json
from decimal import Decimal
from botocore.config import Config

# Initialize Clients
dynamodb = boto3.resource("dynamodb", region_name="us-east-1")

s3_client = boto3.client(
    "s3",
    region_name="us-east-1",
    config=Config(signature_version="s3v4")
)

landmark_table = dynamodb.Table("LookSeeLandmarks")
mapping_table = dynamodb.Table("LookSeeClusterMappings")

BUCKET_NAME = "looksee-models"

# User can be up to 1km away from a landmark/model cluster.
RADIUS_METERS = 1000

# Return at most this many unique downloadable clusters.
MAX_CLUSTERS = 2


class DecimalEncoder(json.JSONEncoder):
    def default(self, obj):
        if isinstance(obj, Decimal):
            return int(obj) if obj % 1 == 0 else float(obj)
        return super().default(obj)


def make_response(status_code, body):
    return {
        "statusCode": status_code,
        "headers": {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Headers": "Content-Type",
            "Access-Control-Allow-Methods": "POST,OPTIONS"
        },
        "body": json.dumps(body, cls=DecimalEncoder)
    }


def find_model_key(cluster_id):
    """
    Looks for this zip file format:

        ml_conversions/{cluster_id}.mlpackage.zip

    Example for cluster 4:

        ml_conversions/4.mlpackage.zip

    This function paginates S3 results so we do not miss files once the
    prefix grows.
    """
    prefix = "ml_conversions/"
    target_filename = f"{cluster_id}.mlpackage.zip"

    kwargs = {
        "Bucket": BUCKET_NAME,
        "Prefix": prefix
    }

    while True:
        response = s3_client.list_objects_v2(**kwargs)

        for obj in response.get("Contents", []):
            key = obj.get("Key", "")

            if key.endswith(target_filename):
                return key

        if not response.get("IsTruncated"):
            break

        kwargs["ContinuationToken"] = response.get("NextContinuationToken")

    return None


def haversine_meters(lat1, lon1, lat2, lon2):
    r = 6371000.0

    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)

    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = (
        math.sin(dphi / 2) ** 2
        + math.cos(phi1)
        * math.cos(phi2)
        * math.sin(dlambda / 2) ** 2
    )

    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    return r * c


def scan_all_landmarks():
    """
    Scans the full LookSeeLandmarks table.

    This handles DynamoDB pagination so we do not silently miss landmarks
    once the table grows past a single scan response.
    """
    items = []
    kwargs = {}

    while True:
        response = landmark_table.scan(**kwargs)
        items.extend(response.get("Items", []))

        if "LastEvaluatedKey" not in response:
            break

        kwargs["ExclusiveStartKey"] = response["LastEvaluatedKey"]

    return items


def get_cluster_mapping_for_landmark(landmark_id):
    """
    Fetches the cluster mapping for one landmark.

    This assumes LookSeeClusterMappings has landmarkId as the partition key.
    """
    try:
        response = mapping_table.get_item(
            Key={
                "landmarkId": landmark_id
            }
        )
    except Exception as e:
        print(f"⚠️ Failed to fetch cluster mapping for {landmark_id}: {e}")
        return None

    item = response.get("Item")

    if not item:
        return None

    cluster_id = item.get("clusterId")

    if cluster_id is None:
        return None

    return str(cluster_id)


def model_sender_handler(event, context):
    try:
        method = (
            event.get("requestContext", {}).get("http", {}).get("method")
            or event.get("httpMethod", "")
        ).upper()

        if method == "OPTIONS":
            return make_response(200, {})

        raw_body = event.get("body") or "{}"
        body = json.loads(raw_body)

        lat = float(body["latitude"])
        lon = float(body["longitude"])

    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as e:
        return make_response(400, {
            "error": f"Invalid input: {str(e)}",
            "expected": "latitude and longitude parameters required"
        })

    items = scan_all_landmarks()

    nearby_landmarks = []

    for item in items:
        try:
            item_lat = float(item.get("latitude"))
            item_lon = float(item.get("longitude"))

            distance = haversine_meters(lat, lon, item_lat, item_lon)

            if distance <= RADIUS_METERS:
                item["_distanceMeters"] = distance
                nearby_landmarks.append(item)

        except (TypeError, ValueError):
            continue

    if not nearby_landmarks:
        return make_response(200, {
            "models": [],
            "reason": "no landmarks found in radius",
            "radiusMeters": RADIUS_METERS,
            "maxClusters": MAX_CLUSTERS,
            "returnedClusterCount": 0,
            "location": {
                "lat": lat,
                "lon": lon
            },
            "objects": []
        })

    clusters_by_id = {}
    objects_by_cluster = {}

    for item in nearby_landmarks:
        landmark_id = item.get("landmarkId")

        if not landmark_id:
            continue

        c_id = get_cluster_mapping_for_landmark(landmark_id)

        if not c_id:
            continue

        try:
            obj_lat = float(item.get("latitude"))
            obj_lon = float(item.get("longitude"))
            distance = float(item.get("_distanceMeters"))
        except (TypeError, ValueError):
            continue

        object_info = {
            "clusterId": c_id,
            "landmarkId": landmark_id,
            "lat": obj_lat,
            "lon": obj_lon,
            "distanceMeters": round(distance, 2)
        }

        if "label" in item:
            object_info["label"] = item.get("label")

        if "shortDescription" in item:
            object_info["shortDescription"] = item.get("shortDescription")

        if c_id not in objects_by_cluster:
            objects_by_cluster[c_id] = []

        objects_by_cluster[c_id].append(object_info)

        if (
            c_id not in clusters_by_id
            or distance < clusters_by_id[c_id]["distanceMeters"]
        ):
            clusters_by_id[c_id] = {
                "clusterId": c_id,
                "distanceMeters": distance,
                "closestLandmarkId": landmark_id,
                "closestLat": obj_lat,
                "closestLon": obj_lon
            }

    if not clusters_by_id:
        return make_response(200, {
            "models": [],
            "reason": "nearby landmarks found but no cluster mappings found",
            "radiusMeters": RADIUS_METERS,
            "maxClusters": MAX_CLUSTERS,
            "returnedClusterCount": 0,
            "location": {
                "lat": lat,
                "lon": lon
            },
            "objects": []
        })

    closest_clusters = sorted(
        clusters_by_id.values(),
        key=lambda x: x["distanceMeters"]
    )

    model_data = []
    selected_cluster_ids = set()

    for cluster in closest_clusters:
        if len(model_data) >= MAX_CLUSTERS:
            break

        c_id = cluster["clusterId"]

        actual_key = find_model_key(c_id)

        if not actual_key:
            print(f"⚠️ No model zip found for cluster {c_id}")
            continue

        url = s3_client.generate_presigned_url(
            "get_object",
            Params={
                "Bucket": BUCKET_NAME,
                "Key": actual_key
            },
            ExpiresIn=3600
        )

        selected_cluster_ids.add(c_id)

        model_data.append({
            "clusterId": c_id,
            "downloadUrl": url,
            "modelKey": actual_key,
            "distanceMeters": round(cluster["distanceMeters"], 2),
            "closestLandmarkId": cluster["closestLandmarkId"],
            "closestObject": {
                "lat": cluster["closestLat"],
                "lon": cluster["closestLon"]
            }
        })

    objects_list = []

    for c_id in selected_cluster_ids:
        objects_list.extend(objects_by_cluster.get(c_id, []))

    objects_list = sorted(
        objects_list,
        key=lambda x: x.get("distanceMeters", float("inf"))
    )

    if len(model_data) == 0:
        reason_str = "nearby clusters found but no downloadable model files found"
    elif len(model_data) == 1:
        reason_str = "single closest downloadable cluster in radius"
    elif len(model_data) == MAX_CLUSTERS:
        reason_str = f"closest {MAX_CLUSTERS} downloadable clusters in radius"
    else:
        reason_str = "closest downloadable clusters in radius"

    return make_response(200, {
        "models": model_data,
        "reason": reason_str,
        "radiusMeters": RADIUS_METERS,
        "maxClusters": MAX_CLUSTERS,
        "returnedClusterCount": len(model_data),
        "location": {
            "lat": lat,
            "lon": lon
        },
        "objects": objects_list
    })