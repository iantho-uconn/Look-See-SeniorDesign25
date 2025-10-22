# run with: python scripts/webcam_test.py
from ultralytics import YOLO
import cv2

model = YOLO("yolo11n.pt")              # downloads weights if missing
# try GPU on Apple silicon; falls back silently if not available
device = "mps"

cap = cv2.VideoCapture(0)                # 0 = default webcam
if not cap.isOpened():
    raise RuntimeError("Webcam not accessible")

while True:
    ok, frame = cap.read()
    if not ok: break

    # run inference; imgsz reduces compute, conf filters weak boxes
    results = model.predict(
        source=frame, stream=False, verbose=False,
        imgsz=416, conf=0.35, device=device
    )
    res = results[0]
    boxes = res.boxes
    xyxy = boxes.xyxy
    conf = boxes.conf
    cls  = boxes.cls
    names = [model.names[int(c)] for c in cls]

    # print one line per detection
    for (x1,y1,x2,y2), p, c in zip(xyxy.cpu().numpy(),
                                conf.cpu().numpy(),
                                cls.cpu().numpy()):
        print(f"[{model.names[int(c)]}] "
            f"box=({x1:.0f},{y1:.0f},{x2:.0f},{y2:.0f}) conf={p:.2f}")

    # draw results onto the frame (Ultralytics helper)
    annotated = results[0].plot()        # returns a NumPy image with boxes/labels

    cv2.imshow("YOLO webcam (q to quit)", annotated)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
