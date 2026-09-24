"""Quick check that the extractor still works, run by CI before the image is built.

Calls extract() directly (no web server needed) on two small pages:
a real article must come back OK, and a near-empty page must come back PAYWALLED.

Run it with:  python smoke_test.py
"""
import main

ARTICLE = (
    "<html><body><article><h1>Repo rate</h1><p>"
    + "The central bank kept the repo rate unchanged this quarter. " * 8
    + "</p></article></body></html>"
)
STUB = "<html><body><p>Subscribe to read.</p></body></html>"

article = main.extract(main.ExtractIn(url="https://example.test/article", html=ARTICLE))
assert article.status == "OK", f"expected OK for a real article, got {article.status}"
assert "repo rate" in article.text, "article text is missing the body"

stub = main.extract(main.ExtractIn(url="https://example.test/stub", html=STUB))
assert stub.status == "PAYWALLED", f"expected PAYWALLED for a near-empty page, got {stub.status}"

print("extractor smoke test passed")
