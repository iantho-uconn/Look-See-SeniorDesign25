import os
from ultralytics import YOLO

DATA_YAML = os.environ.get("DATA_YAML", "/opt/ml/input/data/training/data.yaml")
MODEL_NAME = os.environ.get("MODEL_NAME", "yolo11n.pt")
EPOCHS = int(os.environ.get("EPOCHS", "50"))
IMGSZ = int(os.environ.get("IMGSZ", "640"))
BATCH = int(os.environ.get("BATCH", "16"))
DEVICE = os.environ.get("DEVICE", "cuda")

OUTPUT_DIR = "/opt/ml/model"

def main():
    print(f"DATA_YAML={DATA_YAML}")
    print(f"MODEL_NAME={MODEL_NAME}")
    print(f"EPOCHS={EPOCHS}")
    print(f"IMGSZ={IMGSZ}")
    print(f"BATCH={BATCH}")
    print(f"DEVICE={DEVICE}")

    model = YOLO(MODEL_NAME)

    results = model.train(
        data=DATA_YAML,
        epochs=EPOCHS,
        imgsz=IMGSZ,
        batch=BATCH,
        device=DEVICE,
        project=OUTPUT_DIR,
        name="training_run"
    )

    print("Training complete.")

if __name__ == "__main__":
    main()