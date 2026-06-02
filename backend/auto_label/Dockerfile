FROM python:3.10-slim

RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu

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