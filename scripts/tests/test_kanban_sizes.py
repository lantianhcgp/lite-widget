"""Every allowed size keeps explicit slots and readable textual state."""
import unittest
from test_kanban_visual import module, nodes


class AllSizesVisualTests(unittest.TestCase):
    def test_four_safe_sizes_have_required_slot_counts_and_visible_status_labels(self):
        spec = module.generate()["widget.json"]
        self.assertEqual({"2x2", "4x2", "2x4", "4x4"}, set(spec["variants"]))
        for size, count in {"2x2": 2, "4x2": 4, "2x4": 8, "4x4": 8}.items():
            by_id = {n.get("id"): n for n in nodes(spec["variants"][size]["root"])}
            tasks = [identifier for identifier in by_id if identifier and identifier.startswith("task-")]
            self.assertEqual(count, len(tasks), size)
            for i in range(count):
                self.assertEqual({"field": f"parcel.{i}.name", "op": "exists"}, by_id[f"task-{i}"]["visibleIf"])
                for status, label in LABEL_ITEMS:
                    chip = by_id.get(f"chip-{i}-{status}")
                    self.assertIsNotNone(chip, f"{size}: state text is mandatory")
                    assert chip is not None
                    self.assertTrue(any(n.get("text") == label for n in nodes(chip)))
                self.assertIn(f"unknown-bar-{i}", by_id)
                self.assertEqual(f"parcel.{i}.displayNote", by_id[f"note-{i}"]["bind"]["field"])
                self.assertEqual(f"parcel.{i}.elapsedLabel", by_id[f"elapsed-{i}"]["bind"]["field"])
            self.assertIn("updated", by_id)
            self.assertIn("header-separator", by_id)
            self.assertFalse(any(n.get("bind", {}).get("field", "").endswith(".percent") for n in nodes(spec["variants"][size]["root"])))


LABEL_ITEMS = (("running", "运行中"), ("blocked", "受阻"), ("waiting", "等待"), ("incident", "事故"))
