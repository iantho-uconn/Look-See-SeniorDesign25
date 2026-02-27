import os
import cv2
import torch
import shutil
from autodistill_grounding_dino import GroundingDINO
from autodistill.detection import CaptionOntology

# -----------------------------------------
# 1. DEFINE ALL CLASSES YOU WANT TO DETECT
# -----------------------------------------
# key = class folder name
# value = GroundingDINO text prompt
CLASSES = {
    "UConn": "stone structure",
    # Add more classes anytime...
}

# -----------------------------------------
# 3. DEVICE  this probably will change with AWS setup 
# -----------------------------------------
device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"Using device: {device}")

# -----------------------------------------
# 4. PROCESS EACH CLASS SEPARATELY this is make sure that the dataset is created neatly. Find the format at the end of the file.
# -----------------------------------------
for class_name, prompt in CLASSES.items():

    print(f"\n==============================")
    print(f"Processing class: {class_name}")
    print(f"Prompt: {prompt}")
    unlabeled_dir = f"data\{class_name}"                       #NOTE: this is input folder aka S3 bucket folder where you put the unlabeled images for this class. You can change this to whatever you want, the path construction below should change accordingly. 
    print(f"Unlabeled images folder: {unlabeled_dir}")
    print("==============================")

    # Build ontology for this class
    ontology = CaptionOntology({prompt: class_name})
    model = GroundingDINO(ontology=ontology)

    # Output folders for this class
    dataset_root = os.path.join("dataset", class_name)          #NOTE: this is output folder aka label/image S3 bucket folder where the labeled dataset for each classes will be saved.
    images_out = os.path.join(dataset_root, "images")
    labels_out = os.path.join(dataset_root, "labels")

    os.makedirs(images_out, exist_ok=True)
    os.makedirs(labels_out, exist_ok=True)

    count = 0

    # -----------------------------------------
    # 5. Iterate over unlabeled images         # this make the data yolo community standard ready. no need to change.
    for img_name in os.listdir(unlabeled_dir):
        if not img_name.lower().endswith((".jpg", ".png", ".jpeg")):
            continue

        img_path = os.path.join(unlabeled_dir, img_name)
        image = cv2.imread(img_path)

        detections = model.predict(image)

        h, w, _ = image.shape
        yolo_lines = []

        # Convert detections to YOLO format
        for box, class_id in zip(detections.xyxy, detections.class_id):
            x_min, y_min, x_max, y_max = box

            x_center = ((x_min + x_max) / 2) / w
            y_center = ((y_min + y_max) / 2) / h
            width = (x_max - x_min) / w
            height = (y_max - y_min) / h

            yolo_lines.append(
                f"{int(class_id)} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}"
            )

        # Save image to dataset/<class>/images/
        img_out_path = os.path.join(images_out, img_name)
        shutil.copy(img_path, img_out_path)

        # Save label file to dataset/<class>/labels/
        label_out_path = os.path.join(labels_out, os.path.splitext(img_name)[0] + ".txt")
        with open(label_out_path, "w") as f:
            f.write("\n".join(yolo_lines))

        count += 1

    print(f" Saved {count} images + labels for class '{class_name}' into {dataset_root}")

print("\n Multi-class dataset created successfully!")





"""format of the dataset folder:
dataset/
    UConn/
        images/
        labels/
    Gample/
        images/
        labels/
    ITE/
        images/
        labels/

Comments:
- This can also handle multiple classes at once, just add more entries to the CLASSES dict. The code will automatically create separate folders for each class and save the corresponding images and labels in them.
- We could delete the old unlabeled dataset folder. """
