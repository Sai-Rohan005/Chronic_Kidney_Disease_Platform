import os
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import unittest
from unittest.mock import Mock, patch
from safe_images import download_image


class SafeImageTests(unittest.TestCase):
    def test_only_allowlisted_https_images_are_downloaded(self):
        for url in ["http://res.cloudinary.com/a", "file:///tmp/a", "https://127.0.0.1/a",
                    "https://res.cloudinary.com.evil.invalid/a", "https://user@res.cloudinary.com/a",
                    "https://res.cloudinary.com:8443/a"]:
            with self.subTest(url=url), patch("safe_images.requests.get") as get:
                with self.assertRaises(ValueError):
                    download_image(url)
                get.assert_not_called()

    def test_redirects_are_rejected_and_download_has_a_deadline(self):
        with patch("safe_images.requests.get", return_value=Mock(status_code=302)) as get:
            with self.assertRaises(ValueError):
                download_image("https://res.cloudinary.com/demo/a")
            get.assert_called_once_with("https://res.cloudinary.com/demo/a", allow_redirects=False, timeout=(3, 10))

    def test_success_returns_image_bytes(self):
        response = Mock(status_code=200, content=b"image fixture")
        with patch("safe_images.requests.get", return_value=response):
            self.assertEqual(b"image fixture", download_image("https://res.cloudinary.com/demo/a"))


if __name__ == "__main__":
    unittest.main()
