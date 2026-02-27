Raw uploads go to:
- uploads/raw/images/<userId>/<submissionId>/photo.jpg
- uploads/raw/videos/<userId>/<submissionId>/video.mov

Processed outputs will go to:
- processed/frames/<submissionId>/frame_000001.jpg
- processed/thumbs/<submissionId>/thumb.jpg
- labels/<submissionId>.json (or DynamoDB)
- dataset/v1/manifest.jsonl (or DynamoDB table of samples)