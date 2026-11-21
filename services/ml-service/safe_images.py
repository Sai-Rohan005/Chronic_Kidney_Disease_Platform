"""Restrict the actual image download as well as the Java caller's URL input."""
import os
from urllib.parse import urlsplit
import requests


def download_image(url):
    if not isinstance(url, str):
        raise ValueError("An allow-listed HTTPS image URL is required")
    allowed = {host.strip().lower() for host in os.getenv("PREDICTION_IMAGE_HOSTS", "res.cloudinary.com").split(",") if host.strip()}
    try:
        parsed = urlsplit(url)
        valid = (parsed.scheme == "https" and parsed.hostname in allowed
                 and parsed.username is None and parsed.password is None
                 and parsed.port in (None, 443) and not parsed.fragment
                 and not any(char.isspace() or char == "\\" for char in url))
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("An allow-listed HTTPS image URL is required")
    response = requests.get(url, allow_redirects=False, timeout=(3, 10))
    if response.status_code < 200 or response.status_code >= 300:
        raise ValueError("Image download failed; redirects are not allowed")
    return response.content
