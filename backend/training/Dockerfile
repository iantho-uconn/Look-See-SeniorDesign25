# 1. Use the official PyTorch image with CUDA pre-installed
FROM pytorch/pytorch:2.1.2-cuda12.1-cudnn8-runtime

# Install OpenCV dependencies
RUN apt-get update && apt-get install -y libgl1-mesa-glx libglib2.0-0

WORKDIR /app

COPY train_yolo_sagemaker.py /app/train_yolo_sagemaker.py
COPY requirements.txt /app/requirements.txt
# Copy the tuning recipe directly into the Docker image!
COPY looksee_best_hyperparameters.yaml /app/looksee_best_hyperparameters.yaml

# 2. THE FIX: Force pip to lock in the CUDA 12.1 versions of PyTorch and Torchvision FIRST
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cu121

# 3. Install Python dependencies (pip will now see the CUDA dependencies and leave them alone)
RUN pip install --no-cache-dir -r /app/requirements.txt

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "/app/train_yolo_sagemaker.py"]