import boto3
import time

def triggercoremlconversion_handler(event, context):
    bucket = event["Records"][0]["s3"]["bucket"]["name"]
    best_pt_key = event["Records"][0]["s3"]["object"]["key"]
    input_root = "sagemaker-training-output/"
    
    # Strip the root folder to get the relative path
    relative_path = best_pt_key.replace(input_root, "")
     
    # Folder upload: sagemaker-training-output/CLUSTER_ID/best.pt
    # Direct upload: sagemaker-training-output/CLUSTER_ID.pt
    if "/" in relative_path:
        cluster_id = relative_path.split("/")[0]
    else:
        cluster_id = relative_path.replace(".pt", "")

    s3_input_path = f"s3://{bucket}/{best_pt_key}"
    job_name = f"YOLO-to-CoreML-{int(time.time())}"

    sm = boto3.client("sagemaker")
    sm.create_processing_job(
        ProcessingJobName=job_name,
        RoleArn="arn:aws:iam::637404140724:role/LookSee-Automation-Role",
        AppSpecification={
            "ImageUri": "763104351884.dkr.ecr.us-east-1.amazonaws.com/pytorch-training:2.3.0-cpu-py311-ubuntu20.04-sagemaker",
            "ContainerArguments": ["python", "/opt/ml/processing/input/code/convert.py"],
        },
        Environment={
            "CLUSTER_ID": cluster_id
        },
        NetworkConfig={
            "EnableNetworkIsolation": False,
        },
        ProcessingResources={
            "ClusterConfig": {
                "InstanceCount": 1,
                "InstanceType": "ml.t3.medium",
                "VolumeSizeInGB": 15,
            }
        },
        ProcessingInputs=[
            {
                "InputName": "model_weights",
                "S3Input": {
                    "S3Uri": s3_input_path,
                    "S3DataType": "S3Prefix",
                    "LocalPath": "/opt/ml/processing/input/weights",
                    "S3InputMode": "File",
                },
            },
            {
                "InputName": "code",
                "S3Input": {
                    "S3Uri": f"s3://{bucket}/scripts/convert.py",
                    "S3DataType": "S3Prefix",
                    "LocalPath": "/opt/ml/processing/input/code",
                    "S3InputMode": "File",
                },
            },
        ],
        ProcessingOutputConfig={
            "Outputs": [
                {
                    "OutputName": "coreml_model",
                    "S3Output": {
                        "S3Uri": f"s3://{bucket}/ml_conversions/{cluster_id}/",
                        "LocalPath": "/opt/ml/processing/output",
                        "S3UploadMode": "EndOfJob",
                    },
                }
            ]
        },
    )
    return {"status": "Job Started", "jobName": job_name, "clusterId": cluster_id}