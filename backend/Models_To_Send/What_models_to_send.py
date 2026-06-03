import boto3
import math
import json
from botocore.config import Config

# Initialize Clients
dynamodb = boto3.resource("dynamodb")
s3_client = boto3.client("s3", region_name="us-east-1", config=Config(signature_version='s3v4'))

table = dynamodb.Table("LookSeeLandmarks")
mapping_table = dynamodb.Table("LookSeeClusterMappings")

BUCKET_NAME = "looksee-models"
RADIUS_METERS = 1000  # 1km radius

def find_model_key(cluster_id):
    """
    Looks for the specific zip file format: ml_conversions/[cluster_id].mlpackage.zip
    """
    prefix = "ml_conversions/"
    response = s3_client.list_objects_v2(Bucket=BUCKET_NAME, Prefix=prefix)
    
    if 'Contents' not in response:
        return None

    # Naming Format
    target_filename = f"{cluster_id}.mlpackage.zip"
    
    for obj in response['Contents']:
        key = obj['Key']
        # check if the key ends with the cluster specific zip name
        if key.endswith(target_filename):
            return key
            
    return None

def haversine_meters(lat1, lon1, lat2, lon2):
    r = 6371000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return r * c

def lambda_handler(event, context):
    try:
        raw_body = event.get("body") or "{}"
        body = json.loads(raw_body)
        lat = float(body.get("latitude", 0))
        lon = float(body.get("longitude", 0))
    except (KeyError, ValueError, TypeError) as e:
        return {
            "statusCode": 400,
            "body": json.dumps({
                "error": f"Invalid input: {str(e)}",
                "expected": "latitude and longitude parameters required"
            })
        }

    # Scan all landmarks from database
    items = table.scan().get("Items", [])

    # Filter to only landmarks within 1km radius
    nearby = []
    for item in items:
        try:
            item_lat = float(item.get("latitude"))
            item_lon = float(item.get("longitude"))
            
            if haversine_meters(lat, lon, item_lat, item_lon) <= RADIUS_METERS:
                nearby.append(item)
        except (TypeError, ValueError):
            continue

    # No nearby landmarks found
    if not nearby:
        return {
            "statusCode": 200,
            "body": json.dumps({
                "models": [], 
                "reason": "no models found",
                "location": {"lat": lat, "lon": lon},
                "objects": [] # Added empty objects array to prevent frontend crashes
            })
        }

    # lookup which cluster each nearby landmark belongs to
    cluster_ids = set()
    objects_list = [] # Initialize the new objects array

    for item in nearby:
        landmark_id = item.get("landmarkId") 
        if not landmark_id:
            continue
            
        resp = mapping_table.get_item(Key={"landmarkId": landmark_id})
        
        if "Item" in resp and "clusterId" in resp["Item"]:
            c_id = str(resp["Item"]["clusterId"])
            cluster_ids.add(c_id)
            
            # Extract the exact coordinates of the physical object
            try:
                obj_lat = float(item.get("latitude"))
                obj_lon = float(item.get("longitude"))
                
                # Append to our new objects array
                objects_list.append({
                    "clusterId": c_id,
                    "lat": obj_lat,
                    "lon": obj_lon
                })
            except (TypeError, ValueError):
                continue

    # Generate Presigned URLs for each cluster found
    model_data = []
    for c_id in cluster_ids:
        actual_key = find_model_key(c_id)
        
        if actual_key:
            url = s3_client.generate_presigned_url(
                'get_object',
                Params={'Bucket': BUCKET_NAME, 'Key': actual_key},
                ExpiresIn=3600
            )
            model_data.append({"clusterId": c_id, "downloadUrl": url})
        else:
            model_data.append({"clusterId": c_id, "downloadUrl": None})

    # Determine the reason string based on model count
    if len(model_data) > 1:
        reason_str = "multiple models in radius"
    elif len(model_data) == 1:
        reason_str = "single model in radius"
    else:
        reason_str = "no models found"

    # Output cases
    return {
        "statusCode": 200,
        "body": json.dumps({
            "models": model_data, 
            "reason": reason_str,
            "location": {"lat": lat, "lon": lon},
            "objects": objects_list # Outputting the populated objects array
        })
    }