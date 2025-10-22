# run with: python scripts/folder_test.py
from ultralytics import YOLO

model = YOLO("yolo11n.pt")
device = "mps"

# runs on all images in the folder and saves outputs to runs/detect/predict*
model.predict(
    source="data/JohnathanStatue",
    imgsz=640, conf=0.35, device=device,
    save=True, save_txt=True, save_conf=True
)
