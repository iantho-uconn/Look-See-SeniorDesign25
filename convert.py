import os
os.system('pip install ultralytics')
import shutil
import json
import datetime
from ultralytics import YOLO

input_path = '/opt/ml/processing/input/weights/best.pt'
output_dir = '/opt/ml/processing/output/'

model = YOLO(input_path)
export_path = model.export(format='coreml', nms = True, imgsz=640, int8=False, half=False, optimize=True)

version_id = datetime.datetime.now().strftime("%Y%m%d_%H%M")
final_filename = f"model_{version_id}.mlpackage.zip"

shutil.make_archive(f"model_{version_id}.mlpackage", 'zip', export_path)
shutil.move(f"model_{version_id}.mlpackage.zip", os.path.join(output_dir, final_filename))

manifest_data = {
    "version": version_id,
    "filename": final_filename
}

with open(os.path.join(output_dir, 'manifest.json'), 'w') as f:
    json.dump(manifest_data, f)