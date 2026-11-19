FROM python:3.12-slim

WORKDIR /app

COPY deletion_guard.py /app/deletion_guard.py
COPY build_yolo_dataset_s3_v10_cluster.py /app/build_yolo_dataset_s3_v10_cluster.py

RUN pip install --no-cache-dir boto3

CMD ["python", "build_yolo_dataset_s3_v10_cluster.py"]