FROM python:3.12-slim

WORKDIR /app

COPY build_yolo_dataset_s3_v3.py /app/build_yolo_dataset_s3_v3.py

RUN pip install --no-cache-dir boto3

CMD ["python", "build_yolo_dataset_s3_v3.py"]