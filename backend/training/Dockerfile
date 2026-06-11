# 1. Use the official PyTorch image with CUDA pre-installed
FROM pytorch/pytorch:2.1.2-cuda12.1-cudnn8-runtime


RUN apt-get update && apt-get install -y libgl1-mesa-glx libglib2.0-0

WORKDIR /app

COPY train_yolo_sagemaker.py /app/train_yolo_sagemaker.py
COPY requirements.txt /app/requirements.txt

# 3. Install Python dependencies
RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONUNBUFFERED=1


ENTRYPOINT ["python", "/app/train_yolo_sagemaker.py"]