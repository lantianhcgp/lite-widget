#!/usr/bin/env python3
"""Verify PNGs from the real Android Canvas, never synthesized widget images."""
import argparse
import json
from pathlib import Path
from PIL import Image

COLORS = {"running": (82, 201, 155), "blocked": (232, 187, 83),
          "waiting": (137, 147, 165), "incident": (239, 118, 126)}
BAR = (175, 163, 255)
TRACK = (40, 46, 58)


def verify(folder):
    folder = Path(folder)
    manifest = json.loads((folder / "render-manifest.json").read_text())
    output = {"source": "real Renderer Android native Canvas", "passed": True, "images": {}}
    for name, dimensions in manifest.items():
        path = folder / f"{name}.png"
        image = Image.open(path).convert("RGBA")
        assert list(image.size) == [dimensions["width"], dimensions["height"]], name
        raw = image.tobytes()
        pixels = [tuple(raw[i:i + 4]) for i in range(0, len(raw), 4)]
        counts = {key: sum(1 for value in pixels if value[:3] == rgb and value[3] == 255)
                  for key, rgb in {**COLORS, "bar": BAR, "track": TRACK}.items()}
        slots = {"2x2": 2, "4x2": 4, "2x4": 8, "4x4": 8}[dimensions["classification"]]
        required = ("running", "blocked") if slots == 2 else tuple(COLORS)
        for key in required:
            assert counts[key] >= 10, f"{name}: missing visible {key} status dot"
        assert counts["bar"] >= 16 and counts["track"] >= 16, f"{name}: missing actual progress pixels"
        bright = sum(1 for rgba in pixels if min(rgba[:3]) >= 170 and rgba[3] == 255)
        assert bright >= 100, f"{name}: text is absent/black"
        output["images"][name] = {"size": list(image.size), "classification": dimensions["classification"],
                                 "expected_slots": slots, "solid_pixel_counts": counts,
                                 "bright_text_pixels": bright}
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder")
    parser.add_argument("--output")
    args = parser.parse_args()
    result = verify(args.folder)
    if args.output:
        Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
