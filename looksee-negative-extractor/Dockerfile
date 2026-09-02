FROM public.ecr.aws/lambda/python:3.11

RUN pip install --no-cache-dir \
    numpy==1.26.4 \
    opencv-python-headless==4.9.0.80 \
    boto3

COPY NegativeExtractor.py ${LAMBDA_TASK_ROOT}

CMD ["NegativeExtractor.lambda_handler"]