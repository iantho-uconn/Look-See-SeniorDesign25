import boto3
import time

def triggercoremlconversion_handler(event, context):
    bucket = event['Records'][0]['s3']['bucket']['name']
    best_pt_key = event['Records'][0]['s3']['object']['key']

    input_root = 'sagemaker-training-output/'
    relative_path = best_pt_key.replace(input_root, '') 
    parts = relative_path.split('/')
    cluster_id = parts[0] if len(parts) > 1 else 'default_region'

    s3_input_path = f"s3://{bucket}/{best_pt_key}"
    job_name = f"YOLO-to-CoreML-{int(time.time())}"

    sm = boto3.client('sagemaker')

    response = sm.create_processing_job(
        ProcessingJobName=job_name,
        RoleArn="arn:aws:iam::637404140724:role/LookSee-Automation-Role", 
        AppSpecification={
            'ImageUri': '763104351884.dkr.ecr.us-east-1.amazonaws.com/pytorch-training:1.13.1-cpu-py39-ubuntu20.04-sagemaker',
            'ContainerArguments': ['python', '/opt/ml/processing/input/code/convert.py']
        },
        ProcessingResources={
            'ClusterConfig': {
                'InstanceCount': 1,
                'InstanceType': 'ml.t3.medium', 
                'VolumeSizeInGB': 15
            }
        },
        ProcessingInputs=[
            {
                'InputName': 'model_weights',
                'S3Input': {
                    'S3Uri': s3_input_path,
                    'LocalPath': '/opt/ml/processing/input/weights',
                    'S3DataType': 'S3Prefix',
                    'S3InputMode': 'File'
                }
            },
            {
                'InputName': 'code',
                'S3Input': {
                    'S3Uri': f's3://{bucket}/scripts/convert.py',
                    'LocalPath': '/opt/ml/processing/input/code',
                    'S3DataType': 'S3Prefix',
                    'S3InputMode': 'File'
                }
            }
        ],
        ProcessingOutputConfig={
            'Outputs': [
                {
                    'OutputName': 'coreml_model',
                    'S3Output': {
                        'S3Uri': f's3://{bucket}/ml_conversions/{cluster_id}/', 
                        'LocalPath': '/opt/ml/processing/output',
                        'S3UploadMode': 'EndOfJob'
                    }
                }
            ]
        }
    )

    return {"status": "Job Started", "jobName": job_name, "region": cluster_id}