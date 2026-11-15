"""Elapsed schema remains optional and uses the same bounds as both readers."""
import json
import unittest
from pathlib import Path


class ElapsedSchemaTests(unittest.TestCase):
    def test_elapsed_is_optional_non_negative_integer_with_int32_bound(self):
        path = Path(__file__).resolve().parents[2] / 'app/src/main/assets/kanban-schema.json'
        schema = json.loads(path.read_text())
        task = schema['properties']['tasks']['items']
        self.assertEqual(['name', 'line', 'status', 'progress', 'note'], task['required'])
        self.assertFalse(task['additionalProperties'])
        self.assertIn('elapsed', task['properties'])
        elapsed = task['properties']['elapsed']
        self.assertEqual('integer', elapsed['type'])
        self.assertEqual(0, elapsed['minimum'])
        self.assertEqual(2147483647, elapsed['maximum'])
        self.assertIn('进行中', task['properties']['note']['description'])
