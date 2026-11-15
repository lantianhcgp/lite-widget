"""All variants reserve a visible status lane even for partial data."""
import unittest
from test_kanban_visual import module, nodes


class PartialBoardLayoutTests(unittest.TestCase):
    def test_empty_multi_card_pairs_collapse_with_their_task_slots(self):
        spec = module.generate()['widget.json']
        for size in ('4x4', '4x2'):
            by_id = {n.get('id'): n for n in nodes(spec['variants'][size]['root'])}
            limit = 8 if size == '4x4' else 4
            for index in range(0, limit, 2):
                self.assertEqual({'field': 'parcel.count', 'op': 'gt', 'value': index},
                                 by_id[f'pair-{index}'].get('visibleIf'), size)
