import json
import os
import shutil
import yaml
from typing import Any

from ultralytics import YOLO

# SageMaker standard paths
SM_CHANNEL_TRAINING = os.environ.get(
    "SM_CHANNEL_TRAINING",
    "/opt/ml/input/data/training"
)
SM_MODEL_DIR = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")
SM_OUTPUT_DATA_DIR = os.environ.get(
    "SM_OUTPUT_DATA_DIR",
    "/opt/ml/output/data"
)
SM_HP_FILE = "/opt/ml/input/config/hyperparameters.json"

# The dataset packager writes this file beside data.yaml for every cluster.
LANDMARK_MANIFEST_FILENAME = "landmark-manifest.json"

# Keep this true for the manifest-enabled pipeline so a training job fails
# before model.train() if its input package is incomplete. It can be set to
# false temporarily when running an older dataset that predates manifests.
REQUIRE_LANDMARK_MANIFEST = (
    os.environ.get("REQUIRE_LANDMARK_MANIFEST", "true").lower() == "true"
)

# Defaults
DEFAULTS = {
    "model_name": "yolo11n.pt",
    "epochs": "50",
    "imgsz": "640",
    "batch": "16",
    "device": "0", 
    "patience": "20",
    "run_name": "training_run"
}


def load_hyperparameters() -> dict[str, Any]:
    hps: dict[str, Any] = dict(DEFAULTS)

    if os.path.exists(SM_HP_FILE):
        with open(SM_HP_FILE, "r", encoding="utf-8") as file:
            loaded = json.load(file)

        hps.update(loaded)

    return hps


def find_best_weights(project_dir: str, run_name: str) -> str | None:
    candidate = os.path.join(project_dir, run_name, "weights", "best.pt")
    return candidate if os.path.exists(candidate) else None


def load_and_validate_landmark_manifest(manifest_path: str) -> dict[str, Any]:
    """
    Validate the class-index metadata before a paid training run begins.

    The batch job is the authoritative source of the class order. This check
    verifies that its generated manifest is structurally safe to preserve and
    eventually consume in the iOS app.
    """
    if not os.path.exists(manifest_path):
        raise FileNotFoundError(
            f"{LANDMARK_MANIFEST_FILENAME} not found at: {manifest_path}"
        )

    try:
        with open(manifest_path, "r", encoding="utf-8") as file:
            manifest = json.load(file)
    except json.JSONDecodeError as exc:
        raise ValueError(
            f"Invalid JSON in {manifest_path}: {exc}"
        ) from exc

    if not isinstance(manifest, dict):
        raise ValueError("Landmark manifest root must be a JSON object")

    required_root_fields = {
        "schemaVersion",
        "clusterId",
        "trainingRunId",
        "classCount",
        "landmarks"
    }
    missing_root_fields = sorted(required_root_fields - set(manifest.keys()))

    if missing_root_fields:
        raise ValueError(
            "Landmark manifest is missing required root fields: "
            + ", ".join(missing_root_fields)
        )

    landmarks = manifest.get("landmarks")

    if not isinstance(landmarks, dict):
        raise ValueError("Landmark manifest 'landmarks' must be a JSON object")

    class_count = manifest.get("classCount")

    if not isinstance(class_count, int) or class_count < 0:
        raise ValueError("Landmark manifest 'classCount' must be a non-negative integer")

    if class_count != len(landmarks):
        raise ValueError(
            "Landmark manifest classCount does not match the number of entries: "
            f"classCount={class_count}, entries={len(landmarks)}"
        )

    expected_indexes = set(range(class_count))
    actual_indexes: set[int] = set()

    for key, entry in landmarks.items():
        try:
            class_index = int(key)
        except (TypeError, ValueError) as exc:
            raise ValueError(
                f"Landmark manifest key must be an integer string; received {key!r}"
            ) from exc

        actual_indexes.add(class_index)

        if not isinstance(entry, dict):
            raise ValueError(
                f"Landmark manifest entry {key!r} must be a JSON object"
            )

        required_entry_fields = {
            "classIndex",
            "landmarkId",
            "datasetClassName",
            "label",
            "shortDescription"
        }
        missing_entry_fields = sorted(
            required_entry_fields - set(entry.keys())
        )

        if missing_entry_fields:
            raise ValueError(
                f"Landmark manifest entry {key!r} is missing fields: "
                + ", ".join(missing_entry_fields)
            )

        if entry.get("classIndex") != class_index:
            raise ValueError(
                f"Landmark manifest entry {key!r} has mismatched classIndex "
                f"{entry.get('classIndex')!r}"
            )

        if not str(entry.get("landmarkId", "")).strip():
            raise ValueError(
                f"Landmark manifest entry {key!r} has an empty landmarkId"
            )

        if not str(entry.get("datasetClassName", "")).strip():
            raise ValueError(
                f"Landmark manifest entry {key!r} has an empty datasetClassName"
            )

        if not str(entry.get("label", "")).strip():
            raise ValueError(
                f"Landmark manifest entry {key!r} has an empty display label"
            )

        if not isinstance(entry.get("shortDescription"), str):
            raise ValueError(
                f"Landmark manifest entry {key!r} shortDescription must be a string"
            )

    if actual_indexes != expected_indexes:
        missing_indexes = sorted(expected_indexes - actual_indexes)
        unexpected_indexes = sorted(actual_indexes - expected_indexes)
        raise ValueError(
            "Landmark manifest class indexes are not contiguous from 0 to "
            f"{max(class_count - 1, 0)}. Missing={missing_indexes}, "
            f"unexpected={unexpected_indexes}"
        )

    print(
        "Validated landmark manifest: "
        f"clusterId={manifest['clusterId']}, "
        f"trainingRunId={manifest['trainingRunId']}, "
        f"classCount={class_count}"
    )

    return manifest


def copy_artifacts_to_model_dir(
    best_weights: str | None,
    data_yaml: str,
    landmark_manifest: str
) -> None:
    """
    Files placed in /opt/ml/model are packaged by SageMaker into model.tar.gz.
    """
    os.makedirs(SM_MODEL_DIR, exist_ok=True)

    if best_weights and os.path.exists(best_weights):
        dst_weights = os.path.join(SM_MODEL_DIR, "best.pt")
        shutil.copy2(best_weights, dst_weights)
        print(f"Copied best weights to {dst_weights}")
    else:
        print(
            "WARNING: best.pt not found; "
            "no model weights copied to /opt/ml/model"
        )

    if os.path.exists(data_yaml):
        dst_yaml = os.path.join(SM_MODEL_DIR, "data.yaml")
        shutil.copy2(data_yaml, dst_yaml)
        print(f"Copied data.yaml to {dst_yaml}")
    else:
        raise FileNotFoundError(
            f"Cannot copy data.yaml because it does not exist: {data_yaml}"
        )

    if os.path.exists(landmark_manifest):
        dst_manifest = os.path.join(
            SM_MODEL_DIR,
            LANDMARK_MANIFEST_FILENAME
        )
        shutil.copy2(landmark_manifest, dst_manifest)
        print(
            f"Copied {LANDMARK_MANIFEST_FILENAME} to {dst_manifest}"
        )
    elif REQUIRE_LANDMARK_MANIFEST:
        raise FileNotFoundError(
            "Cannot copy required landmark manifest because it does not exist: "
            f"{landmark_manifest}"
        )
    else:
        print(
            f"WARNING: {LANDMARK_MANIFEST_FILENAME} was not copied because "
            "REQUIRE_LANDMARK_MANIFEST=false and the source file is missing"
        )


def main() -> None:
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError(
            "CRITICAL ERROR: CUDA is not available. PyTorch cannot find the GPU. "
            "Check the Dockerfile PyTorch/CUDA installation."
        )
    print(f"CUDA is available. Found {torch.cuda.device_count()} GPU(s).")
    print(f"Using GPU: {torch.cuda.get_device_name(0)}")

    hps = load_hyperparameters()

    model_name = hps["model_name"]
    epochs = int(hps["epochs"])
    imgsz = int(hps["imgsz"])
    batch = int(hps["batch"])
    device = hps["device"]
    patience = int(hps["patience"])
    run_name = hps["run_name"]

    data_yaml = os.path.join(SM_CHANNEL_TRAINING, "data.yaml")
    landmark_manifest = os.path.join(
        SM_CHANNEL_TRAINING,
        LANDMARK_MANIFEST_FILENAME
    )
    yolo_project_dir = os.path.join(SM_OUTPUT_DATA_DIR, "runs")

    print(f"SM_CHANNEL_TRAINING={SM_CHANNEL_TRAINING}")
    print(f"SM_MODEL_DIR={SM_MODEL_DIR}")
    print(f"SM_OUTPUT_DATA_DIR={SM_OUTPUT_DATA_DIR}")
    print(f"SM_HP_FILE={SM_HP_FILE}")
    print(f"data_yaml={data_yaml}")
    print(f"landmark_manifest={landmark_manifest}")
    print(f"REQUIRE_LANDMARK_MANIFEST={REQUIRE_LANDMARK_MANIFEST}")
    print(f"model_name={model_name}")
    print(f"epochs={epochs}")
    print(f"imgsz={imgsz}")
    print(f"batch={batch}")
    print(f"device={device}")
    print(f"patience={patience}")
    print(f"run_name={run_name}")

    if not os.path.exists(SM_CHANNEL_TRAINING):
        raise FileNotFoundError(
            f"Training channel path does not exist: {SM_CHANNEL_TRAINING}"
        )

    if not os.path.exists(data_yaml):
        raise FileNotFoundError(f"data.yaml not found at: {data_yaml}")

    if REQUIRE_LANDMARK_MANIFEST:
        load_and_validate_landmark_manifest(landmark_manifest)
    elif os.path.exists(landmark_manifest):
        load_and_validate_landmark_manifest(landmark_manifest)
    else:
        print(
            f"WARNING: {LANDMARK_MANIFEST_FILENAME} is missing, but the job "
            "will continue because REQUIRE_LANDMARK_MANIFEST=false"
        )

    # --- START VAL FOLDER FAILSAFE (YAML REWRITE) ---
    val_dir = os.path.join(SM_CHANNEL_TRAINING, "images", "val")
    
    if not os.path.exists(val_dir):
        print(f"⚠️ WARNING: Validation directory not found at {val_dir}.")
        print("Fallback triggered: Modifying data.yaml to use 'train' dataset for validation.")
        
        try:
            with open(data_yaml, 'r') as f:
                yaml_content = yaml.safe_load(f)
            
            # Point the validation path to the train path so YOLO doesn't crash
            yaml_content['val'] = yaml_content['train']
            
            with open(data_yaml, 'w') as f:
                yaml.dump(yaml_content, f)
                
            print("Successfully updated data.yaml fallback.")
        except Exception as e:
            print(f"Failed to modify data.yaml: {e}")
    # --- END VAL FOLDER FAILSAFE ---

    model = YOLO(model_name)

    # Check if we baked the tuned hyperparameters into the Docker image
    custom_cfg = "/app/looksee_best_hyperparameters.yaml"
    
    # Set up our base training arguments
    train_args = {
        "data": data_yaml,
        "epochs": epochs,
        "patience": patience,
        "imgsz": imgsz,
        "batch": batch,
        "device": device,
        "project": yolo_project_dir,
        "name": run_name
    }

    # If the file exists, inject the golden recipe!
    if os.path.exists(custom_cfg):
        print(f"🎯 Found tuned hyperparameters! Injecting {custom_cfg} into training...")
        train_args["cfg"] = custom_cfg
    else:
        print("⚠️ No custom hyperparameters found. Falling back to standard YOLO defaults.")

    # Notice the val flag is completely removed so YOLO relies on our edited data.yaml
    model.train(**train_args)

    best_weights = find_best_weights(yolo_project_dir, run_name)
    print(f"best_weights={best_weights}")

    copy_artifacts_to_model_dir(
        best_weights=best_weights,
        data_yaml=data_yaml,
        landmark_manifest=landmark_manifest
    )

    print("Final /opt/ml/model contents:")
    for filename in sorted(os.listdir(SM_MODEL_DIR)):
        full_path = os.path.join(SM_MODEL_DIR, filename)
        size_bytes = os.path.getsize(full_path)
        print(f"  {filename} ({size_bytes} bytes)")

    print("Training job complete.")


if __name__ == "__main__":
    main()