FROM python:3.11-slim

WORKDIR /app

COPY model_clusters_aws.py /app/model_clusters_aws.py
COPY requirements.txt /app/requirements.txt

RUN pip install --no-cache-dir -r /app/requirements.txt

CMD ["python", "/app/model_clusters_aws.py"]