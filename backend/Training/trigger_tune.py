import sagemaker
from sagemaker.estimator import Estimator
import boto3

# 1. Explicitly tell AWS we are operating in us-east-1 (N. Virginia)
boto_session = boto3.Session(region_name="us-east-1")
sagemaker_session = sagemaker.Session(boto_session=boto_session)

# Updated role pointing to the active training role
role = "arn:aws:iam::637404140724:role/LookSeeSageMakerTrainingRole"

# Notice the :tune tag at the end! This ensures we don't accidentally run the regular training job.
image_uri = "637404140724.dkr.ecr.us-east-1.amazonaws.com/looksee-sagemaker-training:tune"

print("📡 Sending signal to AWS to provision ml.g6.2xlarge GPU for tuning...")

estimator = Estimator(
    image_uri=image_uri,
    role=role,
    sagemaker_session=sagemaker_session, # 2. Pass the region session here
    instance_count=1,
    instance_type="ml.g6.2xlarge", # Your requested powerful GPU
    volume_size=50, 
    max_run=43200, # 12-hour timeout
    output_path="s3://looksee-models/tuning-output/" # Where the winning YAML drops
)

# At the bottom of trigger_tune.py:
estimator.fit(
    {'training': 's3://looksee-models/yolo-packaged-datasets/manual-run-01/b7255d70-755f-48cc-8bf3-15ce946b3a64/'},
    wait=False  # This lets the script finish and exits, leaving the job running in AWS
)