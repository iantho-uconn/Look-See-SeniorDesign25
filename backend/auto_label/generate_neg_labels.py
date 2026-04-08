import os

negative_images = r"C:\Users\nisar\OneDrive - University of Connecticut\Desktop\sem7\SD\personal\github\Looksee\dataset\negatives\images"
negative_labels = r"C:\Users\nisar\OneDrive - University of Connecticut\Desktop\sem7\SD\personal\github\Looksee\dataset\negatives\labels"

os.makedirs(negative_labels, exist_ok=True)

for img_name in os.listdir(negative_images):
    if img_name.lower().endswith((".jpg", ".png", ".jpeg")):
        label_name = os.path.splitext(img_name)[0] + ".txt"
        label_path = os.path.join(negative_labels, label_name)

        # Create an empty label file
        with open(label_path, "w") as f:
            pass

print("✔ Empty label files created for all negative images.")