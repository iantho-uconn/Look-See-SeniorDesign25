from flask import Flask, request, jsonify
from flask_cors import CORS
from models import db, bcrypt, User, Payment
from config import Config
from flask_migrate import Migrate


app = Flask(__name__)
app.config.from_object(Config)
CORS(app)

db.init_app(app)
bcrypt.init_app(app)
migrate = Migrate(app, db)

# temporary debugging routes
@app.route('/debug/tables', methods=['GET'])
def view_tables():
    from sqlalchemy import inspect
    inspector = inspect(db.engine)
    
    tables = {}
    for table_name in inspector.get_table_names():
        columns = inspector.get_columns(table_name)
        tables[table_name] = [col['name'] for col in columns]
    
    return jsonify(tables), 200

@app.route('/debug/users', methods=['GET'])
def view_users():
    users = User.query.all()
    return jsonify([{'id': u.id, 'email': u.email} for u in users]), 200

@app.route('/debug/payments', methods=['GET'])
def view_payments():
    payments = Payment.query.all()
    return jsonify([p.to_dict() for p in payments]), 200

@app.route('/payment', methods=['POST'])
def save_payment():
    data = request.get_json()

#######

    user_id = data.get('user_id')  # passed from the frontend after login
    card_number = data.get('card_number')
    expire_month = data.get('expire_month')
    expire_year = data.get('expire_year')
    cvv = data.get('cvv')

    first_name = data.get('first_name')
    last_name = data.get('last_name')
    state = data.get('state')
    address = data.get('address')
    address2 = data.get('address2')
    zip_code = data.get('zip_code')
    phone = data.get('phone')

    # Validate user exists
    user = User.query.get(user_id)
    if not user:
        return jsonify({'message': 'User not found'}), 404

    # Create payment object
    payment = Payment(
        user_id=user_id,
        expire_month=expire_month,
        expire_year=expire_year,
        first_name=first_name,
        last_name=last_name,
        state=state,
        address=address,
        address2=address2,
        zip_code=zip_code,
        phone=phone
    )

    # Securely hash card number + CVV
    payment.set_card_number(card_number)
    payment.set_cvv(cvv)

    db.session.add(payment)
    db.session.commit()

    return jsonify({
        'message': 'Payment information saved successfully',
        'payment': payment.to_dict()
    }), 201

@app.route('/signup', methods=['POST'])
def signup():
    data = request.get_json()
    email = data.get('email')
    password = data.get('password')

    if User.query.filter_by(email=email).first():
        return jsonify({'message': 'Email already registered'}), 400

    new_user = User(email=email)
    new_user.set_password(password)

    db.session.add(new_user)
    db.session.commit()

    return jsonify({'message': 'User created successfully'}), 201


@app.route('/login', methods=['POST'])
def login():
    data = request.get_json()
    email = data.get('email')
    password = data.get('password')

    user = User.query.filter_by(email=email).first()

    if not user or not user.check_password(password):
        return jsonify({'message': 'Invalid credentials'}), 401

    return jsonify({'message': 'Login successful'}), 200

@app.route('/', methods=['GET'])
def home():
    return jsonify({
        'message': 'Flask Auth API',
        'endpoints': {
            'signup': '/signup [POST]',
            'login': '/login [POST]'
        }
    }), 200

if __name__ == '__main__':
    # with app.app_context():
    #     db.create_all()
    app.run(debug=True)
