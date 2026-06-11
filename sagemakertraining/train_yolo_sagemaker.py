import json
import os
import shutil
from ultralytics import YOLO

# SageMaker standard paths
SM_CHANNEL_TRAINING = os.environ.get("SM_CHANNEL_TRAINING", "/opt/ml/input/data/training")
SM_MODEL_DIR = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")
SM_OUTPUT_DATA_DIR = os.environ.get("SM_OUTPUT_DATA_DIR", "/opt/ml/output/data")
SM_HP_FILE = "/opt/ml/input/config/hyperparameters.json"

# Defaults
DEFAULTS = {
    "model_name": "yolo11n.pt",
    "epochs": "50",
    "imgsz": "640",
    "batch": "16",
    "device": "cuda",
    "patience": "20",
    "run_name": "training_run"
}


def load_hyperparameters():
    hps = dict(DEFAULTS)
    if os.path.exists(SM_HP_FILE):
        with open(SM_HP_FILE, "r") as f:
            loaded = json.load(f)
        hps.update(loaded)
    return hps


def find_best_weights(project_dir: str, run_name: str) -> str | None:
    candidate = os.path.join(project_dir, run_name, "weights", "best.pt")
    return candidate if os.path.exists(candidate) else None


def copy_artifacts_to_model_dir(best_weights: str | None, data_yaml: str):
    os.makedirs(SM_MODEL_DIR, exist_ok=True)

    if best_weights and os.path.exists(best_weights):
        dst = os.path.join(SM_MODEL_DIR, "best.pt")
        shutil.copy2(best_weights, dst)
        print(f"Copied best weights to {dst}")
    else:
        print("WARNING: best.pt not found; no model weights copied to /opt/ml/model")

    if os.path.exists(data_yaml):
        dst_yaml = os.path.join(SM_MODEL_DIR, "data.yaml")
        shutil.copy2(data_yaml, dst_yaml)
        print(f"Copied data.yaml to {dst_yaml}")


def main():
    hps = load_hyperparameters()

    model_name = hps["model_name"]
    epochs = int(hps["epochs"])
    imgsz = int(hps["imgsz"])
    batch = int(hps["batch"])
    device = hps["device"]
    patience = int(hps["patience"])
    run_name = hps["run_name"]

    data_yaml = os.path.join(SM_CHANNEL_TRAINING, "data.yaml")
    yolo_project_dir = os.path.join(SM_OUTPUT_DATA_DIR, "runs")

    print(f"SM_CHANNEL_TRAINING={SM_CHANNEL_TRAINING}")
    print(f"SM_MODEL_DIR={SM_MODEL_DIR}")
    print(f"SM_OUTPUT_DATA_DIR={SM_OUTPUT_DATA_DIR}")
    print(f"SM_HP_FILE={SM_HP_FILE}")
    print(f"data_yaml={data_yaml}")
    print(f"model_name={model_name}")
    print(f"epochs={epochs}")
    print(f"imgsz={imgsz}")
    print(f"batch={batch}")
    print(f"device={device}")
    print(f"patience={patience}")
    print(f"run_name={run_name}")

    if not os.path.exists(SM_CHANNEL_TRAINING):
        raise FileNotFoundError(f"Training channel path does not exist: {SM_CHANNEL_TRAINING}")

    if not os.path.exists(data_yaml):
        raise FileNotFoundError(f"data.yaml not found at: {data_yaml}")

    model = YOLO(model_name)

    model.train(
        data=data_yaml,
        epochs=epochs,
        patience=patience,
        imgsz=imgsz,
        batch=batch,
        device=device,
        project=yolo_project_dir,
        name=run_name
    )

    best_weights = find_best_weights(yolo_project_dir, run_name)
    print(f"best_weights={best_weights}")

    copy_artifacts_to_model_dir(best_weights, data_yaml)

    print("Training job complete.")


if __name__ == "__main__":
    main()