bash''' 
#for folders
yolo predict model=yolo11n.pt source=data/JohnathanStatue save_txt=True save_conf=True device=mps

yolo predict model=yolo11n.pt source=data/sample_video.mp4 device=mps

yolo predict model=yolo11n.pt source=0 imgsz=416 conf=0.35 device=mps


for seeing if everything has label and training image:

python - <<'PY'
import os, glob
def check(split):
    imgs={os.path.splitext(os.path.basename(p))[0] for p in glob.glob(f"data/images/{split}/*")}
    lbls={os.path.splitext(os.path.basename(p))[0] for p in glob.glob(f"data/labels/{split}/*.txt")}
    miss=imgs-lbls
    print(f"{split}: {'OK ✓' if not miss else 'Missing labels for: '+', '.join(sorted(list(miss))[:8])+' ...'}")
check("train"); check("val")
PY


opening labelimg with proper classes set:

python labelImg.py \
  /Users/ian/Desktop/LookSeeSD/yoloTest/data/images/val \
  /Users/ian/Desktop/LookSeeSD/yoloTest/data/labels/val/classes.txt

