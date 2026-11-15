"""Visual contract tests inspect public generated Canvas specs, never a mock renderer."""
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "generate_kanban_template.py"
spec = importlib.util.spec_from_file_location("kanban_template", SCRIPT)
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def nodes(root):
    yield root
    for child in root.get("children", []):
        yield from nodes(child)


class FourByFourVisualTests(unittest.TestCase):
    def setUp(self):
        self.spec = module.generate()["widget.json"]
        self.root = self.spec["variants"]["4x4"]["root"]
        self.by_id = {node.get("id"): node for node in nodes(self.root)}

    def test_all_eight_cards_have_textual_colored_status_pills_and_four_pixel_rails(self):
        for index in range(8):
            for status, label in {"running": "运行中", "blocked": "受阻", "waiting": "等待", "incident": "事故"}.items():
                with self.subTest(slot=index, status=status):
                    chip = self.by_id.get(f"chip-{index}-{status}")
                    self.assertIsNotNone(chip, "a colored dot is not a text status")
                    self.assertEqual({"field": f"parcel.{index}.status", "op": "eq", "value": status}, chip["visibleIf"])
                    self.assertTrue(any(n.get("text") == label for n in nodes(chip)))
                    self.assertGreaterEqual(chip["style"]["radius"], 6)
                    rail = self.by_id[f"rail-{index}-{status}"]
                    self.assertEqual(4, rail["width"])
            self.assertEqual("linear", self.by_id[f"task-{index}"]["style"]["background"]["type"])

    def test_unknown_is_a_full_static_segmented_track_not_a_dash(self):
        for index in range(8):
            bar = self.by_id.get(f"unknown-bar-{index}")
            self.assertIsNotNone(bar, "unknown needs a drawn track")
            self.assertEqual({"field": f"parcel.{index}.progressUnknown", "op": "eq", "value": True}, bar["visibleIf"])
            self.assertGreaterEqual(len(bar["children"]), 6)
            label = self.by_id[f"progress-{index}"]
            self.assertEqual(f"parcel.{index}.progressLabel", label["bind"]["field"])
            known = self.by_id[f"bar-{index}"]
            self.assertEqual(f"parcel.{index}.progress", known["bind"]["field"])
            self.assertEqual(False, known["visibleIf"]["value"])

    def test_each_card_separates_name_status_line_note_and_elapsed(self):
        for index in range(8):
            for identifier, field in (("name", "name"), ("line", "line"), ("note", "displayNote"), ("elapsed", "elapsedLabel")):
                self.assertEqual(f"parcel.{index}.{field}", self.by_id[f"{identifier}-{index}"]["bind"]["field"])
            self.assertGreater(self.by_id[f"name-{index}"]["size"], self.by_id[f"line-{index}"]["size"])
            self.assertGreater(self.by_id[f"line-{index}"]["size"], self.by_id[f"note-{index}"]["size"])
            self.assertEqual(1, self.by_id[f"note-{index}"]["maxLines"])

    def test_header_contains_four_counts_updated_timestamp_and_separator(self):
        header_fields = {n.get("bind", {}).get("field") for n in nodes(self.by_id["header"])}
        self.assertTrue({f"parcel.{s}Count" for s in ("running", "blocked", "incident", "waiting")} <= header_fields)
        self.assertIn("parcel.updated_ms", header_fields)
        self.assertEqual("MM-DD HH:mm", self.by_id["updated"]["bind"]["format"]["date"])
        self.assertEqual(1, self.by_id["header-separator"]["height"])


if __name__ == "__main__":
    unittest.main()
