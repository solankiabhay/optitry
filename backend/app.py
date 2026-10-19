from fastapi import FastAPI
from pydantic import BaseModel
from typing import Literal

app = FastAPI(title="OptiTry Catalog API", version="0.1.0")

class Product(BaseModel):
    id: str
    name: str
    category: Literal["eyeglasses", "sunglasses"]
    image_url: str | None = None
    source: Literal["starter", "user-upload"] = "starter"

products = [Product(id=f"glass-{i:02d}", name=f"Eyeglasses Frame {i:02d}", category="eyeglasses") for i in range(1, 21)] + [Product(id=f"sun-{i:02d}", name=f"Sunglasses Frame {i:02d}", category="sunglasses") for i in range(1, 21)]

@app.get("/health")
def health():
    return {"status": "ok", "service": "optitry-catalog"}

@app.get("/products", response_model=list[Product])
def list_products(category: Literal["eyeglasses", "sunglasses"] | None = None):
    return [p for p in products if category is None or p.category == category]
