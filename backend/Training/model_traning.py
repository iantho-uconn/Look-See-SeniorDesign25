import os
import shutil
import random
from xml.parsers.expat import model
from ultralytics import YOLO

# 1. PATHS
RAW_DATASET = "dataset"          # class-separated dataset
YOLO_DATASET = "dataset_yolo"    # unified YOLO dataset output

# YOLO folder structure
IMG_TRAIN = os.path.join(YOLO_DATASET, "images/train")
IMG_VAL   = os.path.join(YOLO_DATASET, "images/val")
LBL_TRAIN = os.path.join(YOLO_DATASET, "labels/train")
LBL_VAL   = os.path.join(YOLO_DATASET, "labels/val")

# Create folders
for f in [IMG_TRAIN, IMG_VAL, LBL_TRAIN, LBL_VAL]:
    os.makedirs(f, exist_ok=True)

# Train/val split ratio
TRAIN_SPLIT = 0.8


# 2. MERGE ALL CLASS FOLDERS INTO YOLO FORMAT
def merge_dataset():
    NEGATIVE_FOLDER = "negative"

    # All folders inside dataset/
    all_folders = sorted(os.listdir(RAW_DATASET))

    # Real classes = everything except "negative"
    class_names = [f for f in all_folders if f != NEGATIVE_FOLDER]

    # Map class name → class ID
    class_id_map = {cls: idx for idx, cls in enumerate(class_names)}

    print("\nClass → ID mapping:")
    for cls, idx in class_id_map.items():
        print(f"  {cls}: {idx}")

    # 1. Process REAL classes
    for cls in class_names:
        class_path = os.path.join(RAW_DATASET, cls)
        img_dir = os.path.join(class_path, "images")
        lbl_dir = os.path.join(class_path, "labels")

        if not os.path.isdir(img_dir):
            continue

        print(f"\nProcessing class: {cls}")

        for img_name in os.listdir(img_dir):
            if not img_name.lower().endswith((".jpg", ".png", ".jpeg")):
                continue

            img_src = os.path.join(img_dir, img_name)
            lbl_src = os.path.join(lbl_dir, os.path.splitext(img_name)[0] + ".txt")

            # Ensure label exists
            if not os.path.exists(lbl_src):
                with open(lbl_src, "w") as f:
                    pass

            # Rewrite class ID
            new_label_lines = []
            with open(lbl_src, "r") as f:
                for line in f.readlines():
                    parts = line.strip().split()
                    if len(parts) == 5:
                        parts[0] = str(class_id_map[cls])
                        new_label_lines.append(" ".join(parts))

            # Train/val split
            split = "train" if random.random() < TRAIN_SPLIT else "val"

            # Save image
            img_dst = os.path.join(YOLO_DATASET, f"images/{split}", img_name)
            shutil.copy(img_src, img_dst)

            # Save updated label
            lbl_dst = os.path.join(YOLO_DATASET, f"labels/{split}", os.path.splitext(img_name)[0] + ".txt")
            with open(lbl_dst, "w") as f:
                f.write("\n".join(new_label_lines))

    # 2. Process NEGATIVE images (no objects)
  
    neg_path = os.path.join(RAW_DATASET, NEGATIVE_FOLDER)
    neg_img_dir = os.path.join(neg_path, "images")
    neg_lbl_dir = os.path.join(neg_path, "labels")

    if os.path.isdir(neg_img_dir):
        print("\nProcessing NEGATIVE images (no objects):")

        for img_name in os.listdir(neg_img_dir):
            if not img_name.lower().endswith((".jpg", ".png", ".jpeg")):
                continue

            img_src = os.path.join(neg_img_dir, img_name)

            # Train/val split
            split = "train" if random.random() < TRAIN_SPLIT else "val"

            # Save image
            img_dst = os.path.join(YOLO_DATASET, f"images/{split}", img_name)
            shutil.copy(img_src, img_dst)

            # Create empty label file
            lbl_dst = os.path.join(YOLO_DATASET, f"labels/{split}", os.path.splitext(img_name)[0] + ".txt")
            with open(lbl_dst, "w") as f:
                pass  # empty file = no objects

    print("\n🎉 Dataset merged successfully with negative images!")

# 3. CREATE data.yaml FOR YOLO TRAINING
  def create_yaml():
    NEGATIVE_FOLDER = "negative"

    # Only real classes
    class_names = [f for f in sorted(os.listdir(RAW_DATASET)) if f != NEGATIVE_FOLDER]

    yaml_text = f"""
train: {YOLO_DATASET}/images/train
val: {YOLO_DATASET}/images/val

nc: {len(class_names)}
names: {class_names}
"""

    with open("data.yaml", "w") as f:
        f.write(yaml_text.strip())

    print("\n✔ data.yaml created!")


# HYPERPARAMETER TUNING AND TRAINING

def tune_yolo():
    print("\n🧪 Starting YOLO11n hyperparameter tuning...")
    model = YOLO("yolo11n.pt")

    model.tune(
        data="data.yaml",
        imgsz=640,
        epochs=30,        # short runs per trial
        iterations=5,    # how many hyperparameter trials to try
        batch=16,
        device="cuda",

        # You can also fix some things and let it tune the rest
        # lr0, lrf, momentum, weight_decay, etc. will be searched
    )

    print("\n Tuning complete! Best hyperparameters saved in 'tune_results.yaml' (or runs/tune/*).")

import yaml

def load_hyp(path):
    with open(path, "r") as f:
        hyp = yaml.safe_load(f)

    # Fix invalid float → int for close_mosaic
    if "close_mosaic" in hyp:
        hyp["close_mosaic"] = int(hyp["close_mosaic"])

    return hyp


def train_yolo():
    print("\n🚀 Starting YOLO11n training with tuned hyperparameters...")
    model = YOLO("yolo11n.pt")

    # Load tuned hyperparameters
    hyp = load_hyp(r"runs/detect/tune2/best_hyperparameters.yaml")

    model.train(
        data="data.yaml",
        epochs=200,
        patience=20,      # early stopping
        imgsz=640,
        batch=16,
        device="cuda",
        

        **hyp              # inject tuned hyperparameters


    )

    print("\n🎉 Training complete!")


# ---------------------------------------------------------
# MAIN
# ---------------------------------------------------------
if __name__ == "__main__":
    merge_dataset()
    create_yaml()
    #tune_yolo()
    train_yolo()
