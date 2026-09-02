FROM python:3.12-slim

WORKDIR /app

COPY build_yolo_dataset_s3_negtest.py /app/build_yolo_dataset_s3_negtest.py

RUN pip install --no-cache-dir boto3

CMD ["python", "build_yolo_dataset_s3_negtest.py"]