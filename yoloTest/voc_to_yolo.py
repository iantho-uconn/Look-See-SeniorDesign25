# voc_to_yolo.py  (robust)
import argparse, os, glob, xml.etree.ElementTree as ET, shutil
from PIL import Image

# We keep Coke as class 0 from your existing dataset.
# Fruits from Kaggle get 1..3 as agreed.
NAME2ID = {
    "Coke Can": 15,
    "apple": 16,
    "banana": 17,
    "orange": 18,
}

def voc_box_to_yolo(xmin, ymin, xmax, ymax, img_w, img_h):
    # clip to image bounds and guard against zero-sized images
    if img_w <= 0 or img_h <= 0:
        return None
    xmin = max(0.0, min(float(xmin), img_w - 1))
    xmax = max(0.0, min(float(xmax), img_w - 1))
    ymin = max(0.0, min(float(ymin), img_h - 1))
    ymax = max(0.0, min(float(ymax), img_h - 1))
    if xmax <= xmin or ymax <= ymin:
        return None
    x = ((xmin + xmax) / 2.0) / img_w
    y = ((ymin + ymax) / 2.0) / img_h
    w = (xmax - xmin) / img_w
    h = (ymax - ymin) / img_h
    return x, y, w, h

def get_img_size(img_path, xml_size_node):
    iw = ih = 0
    if xml_size_node is not None:
        try:
            iw = int(xml_size_node.findtext("width") or 0)
            ih = int(xml_size_node.findtext("height") or 0)
        except Exception:
            iw = ih = 0
    if iw <= 0 or ih <= 0:
        # fall back to reading the image file
        try:
            with Image.open(img_path) as im:
                iw, ih = im.size
        except Exception:
            iw = ih = 0
    return iw, ih

def convert_folder(src_dir, out_images, out_labels):
    os.makedirs(out_images, exist_ok=True)
    os.makedirs(out_labels, exist_ok=True)

    xmls = sorted(glob.glob(os.path.join(src_dir, "*.xml")))
    converted = 0
    skipped_bad_size = 0
    for x in xmls:
        try:
            tree = ET.parse(x)
            root = tree.getroot()
        except Exception as e:
            print(f"[WARN] Could not parse {x}: {e}")
            continue

        img_filename = root.findtext("filename")
        if not img_filename:
            print(f"[WARN] No <filename> in {x}, skipping.")
            continue

        # image may be alongside XML or in a subdir
        img_path = os.path.join(src_dir, img_filename)
        if not os.path.exists(img_path):
            cand = glob.glob(os.path.join(src_dir, "**", img_filename), recursive=True)
            if cand:
                img_path = cand[0]
            else:
                print(f"[WARN] Image not found for {img_filename}, skipping.")
                continue

        iw, ih = get_img_size(img_path, root.find("size"))
        if iw <= 0 or ih <= 0:
            print(f"[WARN] Zero/unknown size for {img_filename}, skipping labels.")
            # still copy the image
            shutil.copy2(img_path, os.path.join(out_images, img_filename))
            skipped_bad_size += 1
            continue

        base = os.path.splitext(img_filename)[0]
        out_lbl = os.path.join(out_labels, base + ".txt")
        lines = []

        for obj in root.findall("object"):
            name = (obj.findtext("name") or "").strip().lower()
            if name not in NAME2ID:
                # ignore other classes
                continue
            cls_id = NAME2ID[name]
            bnd = obj.find("bndbox")
            if bnd is None:
                continue
            try:
                xmin = float(bnd.findtext("xmin")); ymin = float(bnd.findtext("ymin"))
                xmax = float(bnd.findtext("xmax")); ymax = float(bnd.findtext("ymax"))
            except Exception:
                continue
            norm = voc_box_to_yolo(xmin, ymin, xmax, ymax, iw, ih)
            if norm is None:
                continue
            x, y, w, h = norm
            lines.append(f"{cls_id} {x:.6f} {y:.6f} {w:.6f} {h:.6f}")

        # copy image
        shutil.copy2(img_path, os.path.join(out_images, img_filename))

        if lines:
            with open(out_lbl, "w") as f:
                f.write("\n".join(lines) + "\n")
            converted += 1
        # else: image with no valid fruit boxes; no label file

    print(f"Converted {converted} label files from {src_dir}.  Skipped {skipped_bad_size} with bad size.")
    print("Done.")

if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Folder containing VOC XML + images")
    ap.add_argument("--out_images", required=True)
    ap.add_argument("--out_labels", required=True)
    args = ap.parse_args()
    convert_folder(args.src, args.out_images, args.out_labels)
