FROM python:3.12-slim

WORKDIR /app

COPY build_yolo_dataset_s3_v4_manifest_latlong.py /app/build_yolo_dataset_s3_v4_manifest_latlong.py

RUN pip install --no-cache-dir boto3

CMD ["python", "build_yolo_dataset_s3_v4_manifest_latlong.py"]