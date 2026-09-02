FROM python:3.10-slim

# Install system dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libgl1 \
    libglib2.0-0 \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Upgrade pip first
RUN pip install --upgrade pip

# Install the ultra-stable CUDA 11.8 version for AWS. 
# The --trusted-host flags will completely bypass your SSL Handshake error!
RUN pip install --no-cache-dir torch torchvision torchaudio \
    --index-url https://download.pytorch.org/whl/cu118 \
    --trusted-host download.pytorch.org \
    --trusted-host pypi.org \
    --trusted-host files.pythonhosted.org

# Install the rest of the dependencies
RUN pip install --no-cache-dir \
    boto3 \
    opencv-python-headless \
    autodistill \
    autodistill-grounding-dino \
    scikit-learn \
    roboflow \
    transformers==4.28.1

COPY autolabel.py .

CMD ["python", "autolabel.py"]