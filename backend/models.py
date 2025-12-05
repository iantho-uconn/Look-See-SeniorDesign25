from flask_sqlalchemy import SQLAlchemy
from flask_bcrypt import Bcrypt
from datetime import datetime

db = SQLAlchemy()
bcrypt = Bcrypt()

class User(db.Model):
    __tablename__ = 'users'

    id = db.Column(db.Integer, primary_key=True)
    email = db.Column(db.String(120), unique=True, nullable=False)
    password_hash = db.Column(db.String(128), nullable=False)

    def set_password(self, password):
        self.password_hash = bcrypt.generate_password_hash(password).decode('utf-8')

    def check_password(self, password):
        return bcrypt.check_password_hash(self.password_hash, password)

class Payment(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('users.id'), nullable=False)

    card_number_hash = db.Column(db.String(255), nullable=False)
    expire_month = db.Column(db.Integer, nullable=False)
    expire_year = db.Column(db.Integer, nullable=False)
    cvv_hash = db.Column(db.String(255), nullable=False)

    # Billing details
    first_name = db.Column(db.String(80), nullable=False)
    last_name = db.Column(db.String(80), nullable=False)
    state = db.Column(db.String(80), nullable=False)
    address = db.Column(db.String(255), nullable=False)
    address2 = db.Column(db.String(255))
    zip_code = db.Column(db.String(20), nullable=False)
    phone = db.Column(db.String(20), nullable=False)

    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    # utility methods
    def set_card_number(self, card_number):
        self.card_number_hash = bcrypt.generate_password_hash(card_number).decode('utf-8')

    def check_card_number(self, card_number):
        return bcrypt.check_password_hash(self.card_number_hash, card_number)

    def set_cvv(self, cvv):
        self.cvv_hash = bcrypt.generate_password_hash(cvv).decode('utf-8')

    def to_dict(self):
        return {
            "id": self.id,
            "user_id": self.user_id,
            "expire_month": self.expire_month,
            "expire_year": self.expire_year,
            "first_name": self.first_name,
            "last_name": self.last_name,
            "state": self.state,
            "address": self.address,
            "address2": self.address2,
            "zip_code": self.zip_code,
            "phone": self.phone,
            "created_at": str(self.created_at)
        }