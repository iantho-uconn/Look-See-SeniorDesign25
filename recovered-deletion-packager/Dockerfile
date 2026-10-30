FROM python:3.12-slim-trixie

WORKDIR /app

RUN apt-get update \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

RUN python -m pip install --no-cache-dir --upgrade pip

RUN python -m pip install --no-cache-dir --upgrade \
        boto3 "urllib3>=2.8.0,<3" \
    && python -m pip check

COPY build_yolo_dataset_s3_v8_cluster.py /app/
COPY deletion_guard.py /app/

CMD ["python", "build_yolo_dataset_s3_v8_cluster.py"]
