FROM python:3.12-slim

WORKDIR /app

COPY train_yolo_sagemaker.py /app/train_yolo_sagemaker.py
COPY requirements.txt /app/requirements.txt

RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONUNBUFFERED=1

CMD ["python", "/app/train_yolo_sagemaker.py"]