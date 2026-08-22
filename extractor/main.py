from fastapi import FastAPI
from pydantic import BaseModel
import trafilatura

app = FastAPI(title="extractor")

MIN_TEXT_CHARS = 200


class ExtractIn(BaseModel):
    url: str
    html: str


class ExtractOut(BaseModel):
    url: str
    title: str | None = None
    text: str | None = None
    publishedAt: str | None = None
    status: str


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/extract")
def extract(req: ExtractIn) -> ExtractOut:
    text = trafilatura.extract(req.html, include_comments=False, include_tables=False)
    meta = trafilatura.extract_metadata(filecontent=req.html)

    title = meta.title if meta and meta.title else None
    published_at = str(meta.date) if meta and meta.date else None

    status = "OK" if text and len(text.strip()) >= MIN_TEXT_CHARS else "PAYWALLED"

    return ExtractOut(
        url=req.url,
        title=title,
        text=text,
        publishedAt=published_at,
        status=status,
    )
