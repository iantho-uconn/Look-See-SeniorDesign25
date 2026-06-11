import os
import re
import shutil
import json
import subprocess
from pathlib import Path

INPUT_PT = "/opt/ml/processing/input/weights/best.pt"
OUTPUT_DIR = "/opt/ml/processing/output/"
WHEELHOUSE = "/opt/ml/processing/input/wheels"  # mounted from S3

def extract_cluster_number(cluster_id: str) -> str:
    """
    Examples:
      'ls-mtr010-c3' -> '3'
      'ls-mtr007-c-3' -> '3'
      '3' -> '3'
    Fallback: any digits found, else '0'
    """
    s = (cluster_id or "").strip()

    # Prefer trailing "-c3" or "-c-3"
    m = re.search(r"-c-?(\d+)$", s)
    if m:
        return m.group(1)

    # Else: if string is already digits
    if s.isdigit():
        return s

    # Fallback: any digits anywhere
    digits = "".join(ch for ch in s if ch.isdigit())
    return digits if digits else "0"


def ensure_ultralytics():
    try:
        import ultralytics
        import coremltools
        return
    except ImportError:
        pass

    print("Installing dependencies...")
    # Step 1: upgrade typing_extensions first so cattrs can import NoDefault
    subprocess.check_call([
        "pip", "install", "-q", "--root-user-action=ignore",
        "typing_extensions>=4.14.0"
    ])
    # Step 2: now install the rest
    subprocess.check_call([
        "pip", "install", "-q", "--root-user-action=ignore",
        "coremltools>=7.0",
        "ultralytics",
    ])


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    raw_cluster = os.environ.get("CLUSTER_ID", "0")
    cluster_num = extract_cluster_number(raw_cluster)
    print("DEBUG CLUSTER_ID =", raw_cluster)
    print("DEBUG CLUSTER_NUM =", cluster_num)

    # then in main(), replace pip_install_offline_if_needed() with:
    ensure_ultralytics()

    from ultralytics import YOLO  # import AFTER offline install

    # ---- Export CoreML (.mlpackage folder) ----
    model = YOLO(INPUT_PT)
    export_path = model.export(
        format="coreml",
        nms=True,
        imgsz=640,
        int8=False,
        half=False,
        optimize=True,
    )

    export_dir = os.path.dirname(export_path)
    export_name = os.path.basename(export_path)  # e.g. "best.mlpackage"

    # ---- Zip with desired name: <cluster_num>.mlpackage.zip ----
    base_name = f"{cluster_num}.mlpackage"
    zip_name = f"{base_name}.zip"

    # make_archive writes to current working dir -> "<base_name>.zip"
    shutil.make_archive(base_name, "zip", root_dir=export_dir, base_dir=export_name)
    shutil.move(zip_name, os.path.join(OUTPUT_DIR, zip_name))

    # ---- Also copy weights into output as <cluster_num>.pt ----
    pt_out = f"{cluster_num}.pt"
    shutil.copyfile(INPUT_PT, os.path.join(OUTPUT_DIR, pt_out))

    # ---- manifest.json ----
    manifest = {
        "clusterId": raw_cluster,
        "clusterNumber": cluster_num,
        "coremlZip": zip_name,
        "weightsPt": pt_out
    }
    with open(os.path.join(OUTPUT_DIR, "manifest.json"), "w") as f:
        json.dump(manifest, f)

    print("DONE. Wrote:", zip_name, "and", pt_out)


if __name__ == "__main__":
    main()