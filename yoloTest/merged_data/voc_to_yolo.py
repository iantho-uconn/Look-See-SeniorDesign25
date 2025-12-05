# voc_to_yolo.py
import os, xml.etree.ElementTree as ET, argparse, shutil, random
from pathlib import Path

def xyxy_to_yolo(size, box):
    # box = (xmin, ymin, xmax, ymax), size=(w,h)
    w, h = size
    x_c = (box[0] + box[2]) / 2.0 / w
    y_c = (box[1] + box[3]) / 2.0 / h
    bw  = (box[2] - box[0]) / w
    bh  = (box[3] - box[1]) / h
    return x_c, y_c, bw, bh

parser = argparse.ArgumentParser()
parser.add_argument("--xml_dir", required=True)       # dir with .xml
parser.add_argument("--img_dir", required=True)       # dir with matching .jpg/.png
parser.add_argument("--out_images", required=True)    # merged/images/train or val
parser.add_argument("--out_labels", required=True)    # merged/labels/train or val
parser.add_argument("--classes", nargs="+", required=True)  # class names, order defines IDs
args = parser.parse_args()

CLASSES = {name:i for i,name in enumerate(args.classes)}

Path(args.out_images).mkdir(parents=True, exist_ok=True)
Path(args.out_labels).mkdir(parents=True, exist_ok=True)

for xml_file in Path(args.xml_dir).glob("*.xml"):
    tree = ET.parse(xml_file)
    root = tree.getroot()

    # image file name (assumes same base name)
    fname = root.findtext("filename")
    if fname is None:
        # sometimes stored in path
        fp = root.findtext("path")
        fname = os.path.basename(fp) if fp else xml_file.with_suffix(".jpg").name

    # width/height
    w = int(root.find("size/width").text)
    h = int(root.find("size/height").text)

    # write yolo label
    yolo_lines = []
    for obj in root.findall("object"):
        cls = obj.findtext("name")
        if cls not in CLASSES:  # skip unknown class
            continue
        bnd = obj.find("bndbox")
        xmin = float(bnd.findtext("xmin"))
        ymin = float(bnd.findtext("ymin"))
        xmax = float(bnd.findtext("xmax"))
        ymax = float(bnd.findtext("ymax"))
        x, y, bw, bh = xyxy_to_yolo((w, h), (xmin, ymin, xmax, ymax))
        yolo_lines.append(f"{CLASSES[cls]} {x:.6f} {y:.6f} {bw:.6f} {bh:.6f}")

    # copy image and write label (even if empty -> background image)
    src_img = (Path(args.img_dir) / fname)
    if not src_img.exists():
        # try common alt extensions
        for ext in [".jpg",".jpeg",".png"]:
            cand = src_img.with_suffix(ext)
            if cand.exists():
                src_img = cand; break
    if not src_img.exists():
        print("Missing image for", xml_file); continue

    dst_img = Path(args.out_images) / src_img.name
    shutil.copy2(src_img, dst_img)

    (Path(args.out_labels) / (dst_img.stem + ".txt")).write_text("\n".join(yolo_lines))
