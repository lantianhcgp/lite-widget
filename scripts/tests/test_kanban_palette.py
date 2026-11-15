"""Pixel gate palette must detect the requested running blue, not legacy green."""
import unittest
from test_kanban_pixels import module


class StatePaletteTests(unittest.TestCase):
    def test_pixel_gate_requires_the_same_blue_amber_grey_red_states(self):
        self.assertEqual({'running': (90, 189, 247), 'blocked': (232, 187, 83),
                          'waiting': (137, 147, 165), 'incident': (239, 118, 126)}, module.COLORS)
