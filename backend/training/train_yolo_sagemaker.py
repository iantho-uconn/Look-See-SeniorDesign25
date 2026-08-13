import os
import sys
import subprocess

# 🚀 Force-update ultralytics to support YOLO26 before importing
print("📦 Upgrading ultralytics package for YOLO26 compatibility...")
subprocess.check_call([sys.executable, "-m", "pip", "install", "-U", "ultralytics"])

import json
import shutil
import yaml
from ultralytics import YOLO


SM_CHANNEL_TRAINING = os.environ.get(
    "SM_CHANNEL_TRAINING",
    "/opt/ml/input/data/training",
)

SM_MODEL_DIR = os.environ.get(
    "SM_MODEL_DIR",
    "/opt/ml/model",
)


def load_and_validate_landmark_manifest(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as file:
        manifest = json.load(file)

    if manifest.get("schemaVersion") != 2:
        raise ValueError(
            f"Unsupported schemaVersion: "
            f"{manifest.get('schemaVersion')}. "
            "Only schemaVersion=2 is supported by this training script."
        )

    landmarks = manifest.get("landmarks")

    if not isinstance(landmarks, dict) or not landmarks:
        raise ValueError(
            "The landmark manifest must contain a non-empty "
            "'landmarks' dictionary."
        )

    try:
        class_count = int(manifest["classCount"])
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError(
            "The manifest classCount must be an integer."
        ) from error

    expected_indexes = {
        str(index)
        for index in range(class_count)
    }

    actual_indexes = set(landmarks.keys())

    if actual_indexes != expected_indexes:
        missing_indexes = sorted(
            expected_indexes - actual_indexes,
            key=int,
        )
        extra_indexes = sorted(
            actual_indexes - expected_indexes,
            key=int,
        )

        raise ValueError(
            "Landmark manifest class indexes are not contiguous. "
            f"missing={missing_indexes}, extra={extra_indexes}"
        )

    print(
        "Validated landmark manifest: "
        f"clusterId={manifest.get('clusterId')}, "
        f"trainingRunId={manifest.get('trainingRunId')}, "
        f"classCount={class_count}"
    )

    return manifest


def load_and_validate_data_yaml(data_yaml_path, expected_class_count):
    with open(data_yaml_path, "r", encoding="utf-8") as file:
        data_config = yaml.safe_load(file)

    if not isinstance(data_config, dict):
        raise ValueError(
            "data.yaml must contain a YAML dictionary."
        )

    for required_field in ("train", "val", "names"):
        if required_field not in data_config:
            raise ValueError(
                f"data.yaml is missing required field: "
                f"{required_field}"
            )

    names = data_config["names"]

    if isinstance(names, list):
        yaml_class_count = len(names)

    elif isinstance(names, dict):
        normalized_indexes = {
            int(index)
            for index in names.keys()
        }

        expected_indexes = set(
            range(len(names))
        )

        if normalized_indexes != expected_indexes:
            raise ValueError(
                "data.yaml class indexes are not contiguous. "
                f"Received indexes: {sorted(normalized_indexes)}"
            )

        yaml_class_count = len(names)

    else:
        raise ValueError(
            "data.yaml 'names' must be either a list or dictionary."
        )

    if "nc" in data_config:
        try:
            declared_class_count = int(data_config["nc"])
        except (TypeError, ValueError) as error:
            raise ValueError(
                "data.yaml 'nc' must be an integer."
            ) from error

        if declared_class_count != yaml_class_count:
            raise ValueError(
                "data.yaml class-count mismatch: "
                f"nc={declared_class_count}, "
                f"names count={yaml_class_count}"
            )

    if yaml_class_count != expected_class_count:
        raise ValueError(
            "Class-count mismatch between data.yaml and "
            "landmark-manifest.json: "
            f"data.yaml={yaml_class_count}, "
            f"manifest={expected_class_count}"
        )

    print(
        "Validated data.yaml: "
        f"classCount={yaml_class_count}, "
        f"train={data_config['train']}, "
        f"val={data_config['val']}"
    )

    return data_config


def write_data_yaml(data_yaml_path, data_config):
    with open(data_yaml_path, "w", encoding="utf-8") as file:
        yaml.safe_dump(
            data_config,
            file,
            sort_keys=False,
            allow_unicode=True,
        )


def find_best_weights(run_dir, run_name):
    expected_path = os.path.join(
        run_dir,
        run_name,
        "weights",
        "best.pt",
    )

    if os.path.isfile(expected_path):
        return expected_path

    matches = []

    for root, _, files in os.walk(run_dir):
        if "best.pt" in files:
            matches.append(
                os.path.join(root, "best.pt")
            )

    if not matches:
        return None

    if len(matches) > 1:
        print(
            "WARNING: Multiple best.pt files found. "
            f"Using the newest of {len(matches)} candidates."
        )

        matches.sort(
            key=os.path.getmtime,
            reverse=True,
        )

    return matches[0]


def copy_artifacts_to_model_dir(
    best_weights_path,
    manifest_path,
    data_yaml_path,
):
    required_sources = {
        "model weights": best_weights_path,
        "landmark manifest": manifest_path,
        "data YAML": data_yaml_path,
    }

    for description, source_path in required_sources.items():
        if not source_path or not os.path.isfile(source_path):
            raise FileNotFoundError(
                f"Required {description} not found: "
                f"{source_path}"
            )

    os.makedirs(
        SM_MODEL_DIR,
        exist_ok=True,
    )

    output_artifacts = {
        best_weights_path: os.path.join(
            SM_MODEL_DIR,
            "model.pt",
        ),
        data_yaml_path: os.path.join(
            SM_MODEL_DIR,
            "data.yaml",
        ),
        manifest_path: os.path.join(
            SM_MODEL_DIR,
            "landmark-manifest.json",
        ),
    }

    for source_path, destination_path in output_artifacts.items():
        shutil.copy2(
            source_path,
            destination_path,
        )

        print(
            f"Copied {source_path} "
            f"to {destination_path}"
        )

    expected_outputs = [
        os.path.join(SM_MODEL_DIR, "model.pt"),
        os.path.join(SM_MODEL_DIR, "data.yaml"),
        os.path.join(
            SM_MODEL_DIR,
            "landmark-manifest.json",
        ),
    ]

    missing_outputs = [
        path
        for path in expected_outputs
        if not os.path.isfile(path)
    ]

    if missing_outputs:
        raise FileNotFoundError(
            "Required SageMaker model output files are missing: "
            + ", ".join(missing_outputs)
        )

    empty_outputs = [
        path
        for path in expected_outputs
        if os.path.getsize(path) == 0
    ]

    if empty_outputs:
        raise RuntimeError(
            "One or more SageMaker model output files are empty: "
            + ", ".join(empty_outputs)
        )

    print("\nFiles prepared for SageMaker model.tar.gz:")

    for filename in sorted(os.listdir(SM_MODEL_DIR)):
        path = os.path.join(
            SM_MODEL_DIR,
            filename,
        )

        if os.path.isfile(path):
            print(
                f"  {filename} "
                f"({os.path.getsize(path)} bytes)"
            )


def main():
    print("🚀 Starting YOLO SageMaker Training...")

    data_yaml = os.path.join(
        SM_CHANNEL_TRAINING,
        "data.yaml",
    )

    landmark_manifest = os.path.join(
        SM_CHANNEL_TRAINING,
        "landmark-manifest.json",
    )

    if not os.path.isfile(data_yaml):
        raise FileNotFoundError(
            f"Missing data.yaml at {data_yaml}"
        )

    if not os.path.isfile(landmark_manifest):
        raise FileNotFoundError(
            "Missing required landmark manifest at "
            f"{landmark_manifest}"
        )

    manifest = load_and_validate_landmark_manifest(
        landmark_manifest
    )

    manifest_class_count = int(
        manifest["classCount"]
    )

    train_dir = os.path.join(
        SM_CHANNEL_TRAINING,
        "images",
        "train",
    )

    val_dir = os.path.join(
        SM_CHANNEL_TRAINING,
        "images",
        "val",
    )

    if (
        not os.path.isdir(train_dir)
        and not os.path.isdir(val_dir)
    ):
        raise RuntimeError(
            "CRITICAL: Both train and val image directories "
            "are missing. No images were packaged."
        )

    with open(data_yaml, "r", encoding="utf-8") as file:
        yaml_content = yaml.safe_load(file)

    if not isinstance(yaml_content, dict):
        raise ValueError(
            "data.yaml must contain a YAML dictionary."
        )

    if not os.path.isdir(train_dir):
        print(
            "⚠️ WARNING: train directory is missing. "
            "Using the validation split for training."
        )

        if "val" not in yaml_content:
            raise ValueError(
                "Cannot replace the missing train split because "
                "data.yaml does not contain 'val'."
            )

        yaml_content["train"] = yaml_content["val"]

    if not os.path.isdir(val_dir):
        print(
            "⚠️ WARNING: val directory is missing. "
            "Using the training split for validation."
        )

        if "train" not in yaml_content:
            raise ValueError(
                "Cannot replace the missing val split because "
                "data.yaml does not contain 'train'."
            )

        yaml_content["val"] = yaml_content["train"]

    write_data_yaml(
        data_yaml,
        yaml_content,
    )

    load_and_validate_data_yaml(
        data_yaml,
        expected_class_count=manifest_class_count,
    )

    model = YOLO("yolo26s.pt")

    custom_cfg = (
        "/app/looksee_best_hyperparameters.yaml"
    )

    yolo_project_dir = (
        "/opt/ml/output/data/runs"
    )

    run_name = "training_run"

    train_args = {
        "data": data_yaml,
        "epochs": 100,
        "patience": 15,
        "imgsz": 640,
        "batch": 32,
        "optimizer": "SGD",
        "device": 0,
        "mixup": 0.15,        # This removes background randomly, helps to focus on traning the object itself.
        "dropout": 0.1,       # Randomly shuts of neturons while training, helping other neturons to learn more, this might to harmful in some case like if the dataset is small.
        "workers": 8,
        #cache=True,          # Only if we have enough RAM. 
        "project": yolo_project_dir,  
        "name": run_name,
    }

    if os.path.isfile(custom_cfg):
        print(
            "🎯 Found tuned hyperparameters. "
            f"Injecting {custom_cfg} into training..."
        )

        # 🚀 ACTIVATED CUSTOM TUNED HYPERPARAMETERS:
        train_args["cfg"] = custom_cfg

    else:
        print(
            "⚠️ No custom hyperparameters found. "
            "Using standard YOLO defaults."
        )

    model.train(
        **train_args
    )

    best_weights = find_best_weights(
        yolo_project_dir,
        run_name,
    )

    print(
        f"best_weights={best_weights}"
    )

    copy_artifacts_to_model_dir(
        best_weights_path=best_weights,
        manifest_path=landmark_manifest,
        data_yaml_path=data_yaml,
    )

    print(
        "✅ Training and model artifact packaging completed."
    )


if __name__ == "__main__":
    main()