### to run locally, you need to run python in a virtual machine in backend folder

# run for first time
```
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install flask flask_sqlalchemy flask_bcrypt flask_cors python-dotenv psycopg2-binary
```

### every other time
`source venv/bin/activate`

### run app
`python3 app.py`

### deactivate virtual environment
`deactivate`