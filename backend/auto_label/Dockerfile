FROM python:3.10-slim

# Install system dependencies
RUN apt-get update && apt-get install -y \
    ca-certificates \
    libgl1 \
    libglib2.0-0 \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Upgrade pip and install PyTorch using default PyPI repository
# This avoids the custom index URL that is triggering the SSL Handshake error
RUN pip install --upgrade pip
RUN pip install --no-cache-dir torch torchvision torchaudio

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