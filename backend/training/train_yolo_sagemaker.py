import os
import sys
import json
import yaml
import shutil
import glob
from ultralytics import YOLO

SM_CHANNEL_TRAINING = os.environ.get("SM_CHANNEL_TRAINING", "/opt/ml/input/data/training")
SM_MODEL_DIR = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")

def load_and_validate_landmark_manifest(manifest_path):
    with open(manifest_path, 'r') as f:
        manifest = json.load(f)

    if manifest.get('schemaVersion') != 2:
        raise ValueError(
            f"Unsupported schemaVersion: {manifest.get('schemaVersion')}. "
            "Only schemaVersion=2 is supported by this training script."
        )

    landmarks = manifest.get('landmarks', {})
    if not landmarks:
        raise ValueError("The 'landmarks' dictionary is empty.")

    print(f"Validated landmark manifest: clusterId={manifest.get('clusterId')}, "
          f"trainingRunId={manifest.get('trainingRunId')}, "
          f"classCount={manifest.get('classCount')}")


def find_best_weights(run_dir, run_name):
    # YOLO typically saves to `runs/detect/run_name/weights/best.pt`
    target = os.path.join(run_dir, run_name, "weights", "best.pt")
    if os.path.exists(target):
        return target
    
    # Fallback to search if the directory structure is unexpected
    for root, _, files in os.walk(run_dir):
        if "best.pt" in files:
            return os.path.join(root, "best.pt")
    
    return None

def copy_artifacts_to_model_dir(best_weights_path, manifest_path):
    if not best_weights_path or not os.path.exists(best_weights_path):
        raise FileNotFoundError(f"Best weights not found at {best_weights_path}")
        
    os.makedirs(SM_MODEL_DIR, exist_ok=True)
    
    # Copy weights
    dest_weights = os.path.join(SM_MODEL_DIR, "model.pt")
    shutil.copy(best_weights_path, dest_weights)
    print(f"Copied weights to {dest_weights}")

    # Copy manifest
    if os.path.exists(manifest_path):
        dest_manifest = os.path.join(SM_MODEL_DIR, "landmark-manifest.json")
        shutil.copy(manifest_path, dest_manifest)
        print(f"Copied manifest to {dest_manifest}")
    else:
        print(f"WARNING: Manifest not found at {manifest_path}, skipping copy.")


def main():
    print("🚀 Starting YOLO SageMaker Training...")
    data_yaml = os.path.join(SM_CHANNEL_TRAINING, "data.yaml")
    landmark_manifest = os.path.join(SM_CHANNEL_TRAINING, "landmark-manifest.json")
    
    if not os.path.exists(data_yaml):
        raise FileNotFoundError(f"Missing data.yaml at {data_yaml}")

    # Ensure schemaVersion=2 manifest is valid
    if os.path.exists(landmark_manifest):
        load_and_validate_landmark_manifest(landmark_manifest)
    else:
        print("WARNING: landmark-manifest.json is missing!")

    train_dir = os.path.join(SM_CHANNEL_TRAINING, "images", "train")
    val_dir = os.path.join(SM_CHANNEL_TRAINING, "images", "val")
    
    # --- FAILSAFE 1: Empty Cluster Check ---
    if not os.path.exists(train_dir) and not os.path.exists(val_dir):
        raise RuntimeError("CRITICAL: Both 'train' and 'val' dirs missing. No images packaged!")

    # --- FAILSAFE 2: Train missing (Caused by Boosting hashing all copies to val) ---
    if not os.path.exists(train_dir):
        print("⚠️ WARNING: 'train' directory missing! Modifying data.yaml to use 'val' for training.")
        try:
            with open(data_yaml, 'r') as f:
                yaml_content = yaml.safe_load(f)
            yaml_content['train'] = yaml_content['val']
            with open(data_yaml, 'w') as f:
                yaml.dump(yaml_content, f)
            print("Successfully updated data.yaml fallback for missing train dir.")
        except Exception as e:
            print(f"Failed to modify data.yaml: {e}")

    # --- FAILSAFE 3: Val missing (Caused by Boosting hashing all copies to train) ---
    if not os.path.exists(val_dir):
        print("⚠️ WARNING: 'val' directory missing! Modifying data.yaml to use 'train' for validation.")
        try:
            with open(data_yaml, 'r') as f:
                yaml_content = yaml.safe_load(f)
            yaml_content['val'] = yaml_content['train']
            with open(data_yaml, 'w') as f:
                yaml.dump(yaml_content, f)
            print("Successfully updated data.yaml fallback for missing val dir.")
        except Exception as e:
            print(f"Failed to modify data.yaml: {e}")

    # Load YOLO
    model = YOLO("yolo11n.pt")
    custom_cfg = "/app/looksee_best_hyperparameters.yaml"
    
    yolo_project_dir = "/opt/ml/output/data/runs"
    run_name = "training_run"

    # Set up our base training arguments
    train_args = {
        "data": data_yaml,
        "epochs": 50,
        "patience": 20,
        "imgsz": 640,
        "batch": 16,
        "device": 0,
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

    copy_artifacts_to_model_dir(best_weights, landmark_manifest)

if __name__ == "__main__":
    main()