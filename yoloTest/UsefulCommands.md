bash''' 
#for folders
yolo predict model=yolo11n.pt source=data/JohnathanStatue save_txt=True save_conf=True device=mps

#
yolo predict model=yolo11n.pt source=data/sample_video.mp4 device=mps

yolo predict model=yolo11n.pt source=0 imgsz=416 conf=0.35 device=mps
