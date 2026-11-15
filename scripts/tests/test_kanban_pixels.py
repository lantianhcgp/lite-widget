"""Checker unit fixtures prove the checker; they are not widget render evidence."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image

SCRIPT = Path(__file__).resolve().parents[1] / "verify_kanban_pixels.py"
spec = importlib.util.spec_from_file_location("verify_kanban_pixels", SCRIPT)
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PixelCheckerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.image = Image.new("RGBA", (180, 180), (18, 22, 31, 255))
        colors = [*module.COLORS.values(), module.BAR, module.TRACK, (237, 240, 245)]
        for i, rgb in enumerate(colors):
            for y in range(10, 30):
                for x in range(10 + 20 * i, 25 + 20 * i):
                    self.image.putpixel((x, y), (*rgb, 255))
        (self.root / "render-manifest.json").write_text(json.dumps({
            "unit-fixture": {"width": 180, "height": 180, "classification": "4x4"}}))

    def check(self):
        self.image.save(self.root / "unit-fixture.png")
        return module.verify(self.root)

    def test_checker_accepts_explicit_unit_fixture(self):
        self.assertTrue(self.check()["passed"])

    def test_checker_rejects_blank_and_missing_status(self):
        self.image = Image.new("RGBA", (180, 180), (18, 22, 31, 255))
        with self.assertRaises(AssertionError):
            self.check()

    def test_checker_rejects_wrong_dimensions(self):
        self.image = self.image.resize((179, 180))
        with self.assertRaises(AssertionError):
            self.check()

    def test_checker_rejects_absent_progress_bar(self):
        for y in range(10, 30):
            for x in range(90, 105):
                self.image.putpixel((x, y), (18, 22, 31, 255))
        with self.assertRaises(AssertionError):
            self.check()


if __name__ == "__main__":
    unittest.main()
