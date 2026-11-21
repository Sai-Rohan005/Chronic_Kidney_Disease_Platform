from fastapi import FastAPI
from .observability import configure_logging, RequestCorrelationMiddleware
configure_logging()
from .model_assets import validate_assets
validate_assets()
from .security import JwtValidator, install_security
from .routes import text_extractor_router, tab_trans_router
from .routes.image_classification import router as image_classification_router


app = FastAPI()
install_security(app, JwtValidator.from_environment())

# include your OCR routes

app.include_router(text_extractor_router, prefix="/routes")
app.include_router(tab_trans_router, prefix="/routes")
app.include_router(image_classification_router, prefix="/routes")

@app.get("/")
def root():
    return {"msg": "Hello from fast API"}

# Wrap the whole ASGI app, including authentication failures and unhandled 500 responses.
app = RequestCorrelationMiddleware(app)
