import os
import shutil
from ultralytics import YOLO

# SageMaker standard paths
SM_CHANNEL_TRAINING = os.environ.get("SM_CHANNEL_TRAINING", "/opt/ml/input/data/training")
SM_MODEL_DIR = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")
SM_OUTPUT_DATA_DIR = os.environ.get("SM_OUTPUT_DATA_DIR", "/opt/ml/output/data")

# Training config from env vars
MODEL_NAME = os.environ.get("MODEL_NAME", "yolo11n.pt")
EPOCHS = int(os.environ.get("EPOCHS", "50"))
IMGSZ = int(os.environ.get("IMGSZ", "640"))
BATCH = int(os.environ.get("BATCH", "16"))
DEVICE = os.environ.get("DEVICE", "cuda")
PATIENCE = int(os.environ.get("PATIENCE", "20"))

# Expected packaged dataset layout
DATA_YAML = os.environ.get("DATA_YAML", os.path.join(SM_CHANNEL_TRAINING, "data.yaml"))

# Ultralytics output area inside container
YOLO_PROJECT_DIR = os.path.join(SM_OUTPUT_DATA_DIR, "runs")
YOLO_RUN_NAME = os.environ.get("RUN_NAME", "training_run")


def find_best_weights(project_dir: str, run_name: str) -> str | None:
    """
    Ultralytics usually writes:
      <project_dir>/<run_name>/weights/best.pt
    """
    candidate = os.path.join(project_dir, run_name, "weights", "best.pt")
    return candidate if os.path.exists(candidate) else None


def copy_artifacts_to_model_dir(best_weights: str | None):
    """
    SageMaker expects final model artifacts under /opt/ml/model.
    Everything in SM_MODEL_DIR gets bundled into model.tar.gz.
    """
    os.makedirs(SM_MODEL_DIR, exist_ok=True)

    if best_weights and os.path.exists(best_weights):
        dst = os.path.join(SM_MODEL_DIR, "best.pt")
        shutil.copy2(best_weights, dst)
        print(f"Copied best weights to {dst}")
    else:
        print("WARNING: best.pt not found; no model weights copied to /opt/ml/model")

    # Also copy data.yaml for reference/debugging
    if os.path.exists(DATA_YAML):
        dst_yaml = os.path.join(SM_MODEL_DIR, "data.yaml")
        shutil.copy2(DATA_YAML, dst_yaml)
        print(f"Copied data.yaml to {dst_yaml}")


def validate_inputs():
    print("Validating training inputs...")
    print(f"SM_CHANNEL_TRAINING={SM_CHANNEL_TRAINING}")
    print(f"SM_MODEL_DIR={SM_MODEL_DIR}")
    print(f"SM_OUTPUT_DATA_DIR={SM_OUTPUT_DATA_DIR}")
    print(f"DATA_YAML={DATA_YAML}")
    print(f"MODEL_NAME={MODEL_NAME}")
    print(f"EPOCHS={EPOCHS}")
    print(f"IMGSZ={IMGSZ}")
    print(f"BATCH={BATCH}")
    print(f"DEVICE={DEVICE}")
    print(f"PATIENCE={PATIENCE}")
    print(f"YOLO_PROJECT_DIR={YOLO_PROJECT_DIR}")
    print(f"YOLO_RUN_NAME={YOLO_RUN_NAME}")

    if not os.path.exists(SM_CHANNEL_TRAINING):
        raise FileNotFoundError(f"Training channel path does not exist: {SM_CHANNEL_TRAINING}")

    if not os.path.exists(DATA_YAML):
        raise FileNotFoundError(f"data.yaml not found at: {DATA_YAML}")


def main():
    validate_inputs()

    print("Loading YOLO model...")
    model = YOLO(MODEL_NAME)

    print("Starting training...")
    model.train(
        data=DATA_YAML,
        epochs=EPOCHS,
        patience=PATIENCE,
        imgsz=IMGSZ,
        batch=BATCH,
        device=DEVICE,
        project=YOLO_PROJECT_DIR,
        name=YOLO_RUN_NAME
    )

    print("Training finished. Looking for best weights...")
    best_weights = find_best_weights(YOLO_PROJECT_DIR, YOLO_RUN_NAME)
    print(f"best_weights={best_weights}")

    copy_artifacts_to_model_dir(best_weights)

    print("Training job complete.")


if __name__ == "__main__":
    main()