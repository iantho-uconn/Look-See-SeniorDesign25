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

    # draw results onto the frame (Ultralytics helper)
    annotated = results[0].plot()        # returns a NumPy image with boxes/labels

    cv2.imshow("YOLO webcam (q to quit)", annotated)
    if cv2.waitKey(1) & 0xFF == ord('q'):
        break

cap.release()
cv2.destroyAllWindows()
