import os
import shutil
from ultralytics import YOLO

# SageMaker injects the S3 dataset into this hidden folder automatically
SM_CHANNEL_TRAINING = os.environ.get("SM_CHANNEL_TRAINING", "/opt/ml/input/data/training")
SM_MODEL_DIR = os.environ.get("SM_MODEL_DIR", "/opt/ml/model")

def main():
    import torch
    if not torch.cuda.is_available():
        raise RuntimeError("CRITICAL ERROR: CUDA is not available during tuning.")
    print(f"CUDA is available. Found {torch.cuda.device_count()} GPU(s).")

    print("🚀 Starting LookSee Full-Cluster Hyperparameter Tuning...")
    
    # SageMaker will download the master data.yaml your packager created
    data_yaml = os.path.join(SM_CHANNEL_TRAINING, "data.yaml")
    
    if not os.path.exists(data_yaml):
        raise FileNotFoundError(f"❌ ERROR: Cannot find dataset config at: {data_yaml}")

    # Load the base model using YOLO11 (matching your training script defaults)
    model = YOLO("yolo11n.pt") 
    
    # Start the Tuning Process across the dataset
    model.tune(
        data=data_yaml, 
        epochs=15,        # 15 epochs per mutation
        iterations=15,    # 15 genetic mutations to test
        optimizer="AdamW",
        plots=False,      # Turn off plotting to save compute time
        save=False        # Don't save large weight files, we only want the hyperparameters
    )
    
    print("✅ Tuning Complete! Locating best hyperparameters...")

    # Define where YOLO saves the output relative to our Dockerfile /app WORKDIR
    best_yaml_path = "/app/runs/detect/tune/best_hyperparameters.yaml"
    
    if os.path.exists(best_yaml_path):
        os.makedirs(SM_MODEL_DIR, exist_ok=True)
        destination = os.path.join(SM_MODEL_DIR, "looksee_best_hyperparameters.yaml")
        shutil.copy(best_yaml_path, destination)
        print(f"📦 Successfully copied winning config to {destination}")
    else:
        print("❌ ERROR: Tuning failed. best_hyperparameters.yaml not found.")

if __name__ == "__main__":
    main()