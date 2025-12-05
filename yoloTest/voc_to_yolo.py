# voc_to_yolo.py
import argparse, os, glob, xml.etree.ElementTree as ET, shutil

# Map VOC class names -> YOLO indices. Edit names to match your XML exactly.
NAME2ID = {
    "dog": 0,       # not used in Kaggle set, but kept for consistency
    "person": 1,
    "cat": 2,
    "tv": 3,
    "car": 4,
    "meatballs": 5,
    "marinara sauce": 6,
    "tomato soup": 7,
    "chicken noodle soup": 8,
    "french onion soup": 9,
    "chicken breast": 10,
    "ribs": 11,
    "pulled pork": 12,
    "hamburger": 13,
    "cavity": 14,
    "Coke Zero": 15,
    "apple": 16,
    "banana": 17,
    "orange": 18,
}

def voc_box_to_yolo(xmin, ymin, xmax, ymax, img_w, img_h):
    x = (xmin + xmax) / 2.0 / img_w
    y = (ymin + ymax) / 2.0 / img_h
    w = (xmax - xmin) / img_w
    h = (ymax - ymin) / img_h
    return x, y, w, h

def convert_folder(src_dir, out_images, out_labels):
    os.makedirs(out_images, exist_ok=True)
    os.makedirs(out_labels, exist_ok=True)

    xmls = sorted(glob.glob(os.path.join(src_dir, "*.xml")))
    count = 0
    for x in xmls:
        tree = ET.parse(x)
        root = tree.getroot()

        # image file name
        img_filename = root.findtext("filename")
        if not img_filename:
            continue
        img_path = os.path.join(src_dir, img_filename)
        if not os.path.exists(img_path):
            # some VOCs put images in a subfolder; try to find it
            cand = glob.glob(os.path.join(src_dir, "**", img_filename), recursive=True)
            if cand:
                img_path = cand[0]
            else:
                continue

        # image size
        size = root.find("size")
        iw = int(size.findtext("width"))
        ih = int(size.findtext("height"))

        # output label path
        base = os.path.splitext(img_filename)[0]
        out_lbl = os.path.join(out_labels, base + ".txt")

        lines = []
        for obj in root.findall("object"):
            name = obj.findtext("name")
            if name not in NAME2ID:
                # skip unknown classes to avoid wrong indices
                continue
            cls_id = NAME2ID[name]
            bnd = obj.find("bndbox")
            xmin = float(bnd.findtext("xmin")); ymin = float(bnd.findtext("ymin"))
            xmax = float(bnd.findtext("xmax")); ymax = float(bnd.findtext("ymax"))
            x, y, w, h = voc_box_to_yolo(xmin, ymin, xmax, ymax, iw, ih)
            lines.append(f"{cls_id} {x:.6f} {y:.6f} {w:.6f} {h:.6f}")

        if not lines:
            # still copy image, but no label file
            shutil.copy2(img_path, os.path.join(out_images, img_filename))
            continue

        # copy image and write label
        shutil.copy2(img_path, os.path.join(out_images, img_filename))
        with open(out_lbl, "w") as f:
            f.write("\n".join(lines))
        count += 1

    print(f"Converted {count} label files from {src_dir}")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="Folder containing VOC XML + images")
    ap.add_argument("--out_images", required=True)
    ap.add_argument("--out_labels", required=True)
    args = ap.parse_args()
    convert_folder(args.src, args.out_images, args.out_labels)
