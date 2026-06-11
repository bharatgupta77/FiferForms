"""Tests for the parts that carry real risk: derived operations, conditional
visibility in validation, and reconciling untrusted model output.

Runs with either:
    python3 tests/test_core.py
    python3 -m pytest
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.config_loader import load_config
from app.validator import validate_record, is_visible
from app.derive import compute_derived
from app.llm.coerce import reconcile


class TestDerived(unittest.TestCase):
    def test_sum_product(self):
        cfg = load_config("expense_report")
        rec = {"line_items": [{"quantity": 2, "unit_price": 50},
                              {"quantity": 1, "unit_price": 120.5}]}
        self.assertEqual(compute_derived(rec, cfg)["total"], 220.5)

    def test_sum_product_coerces_and_skips_junk(self):
        cfg = load_config("expense_report")
        rec = {"line_items": [{"quantity": "3", "unit_price": "10"},
                              "not-a-dict", {"quantity": None}]}
        self.assertEqual(compute_derived(rec, cfg)["total"], 30.0)

    def test_sum_over_group(self):
        cfg = load_config("workout_log")
        rec = {"exercises": [{"sets": 5}, {"sets": 3}]}
        self.assertEqual(compute_derived(rec, cfg)["total_sets"], 8.0)

    def test_unknown_operation_raises(self):
        cfg = {"fields": [{"id": "x", "type": "number",
                           "derived": {"operation": "nope"}}]}
        with self.assertRaises(ValueError):
            compute_derived({}, cfg)


class TestVisibilityValidation(unittest.TestCase):
    def setUp(self):
        self.cfg = load_config("expense_report")
        self.base = {"vendor": "Acme", "expense_date": "2026-06-01",
                     "line_items": [{"description": "x", "quantity": 1,
                                     "unit_price": 1}]}

    def test_hidden_field_not_required(self):
        rec = {**self.base, "category": "Meals"}  # receipt_number hidden
        self.assertEqual(validate_record(rec, self.cfg)["errors"], {})

    def test_visible_optional_field_ok_when_absent(self):
        rec = {**self.base, "category": "Equipment"}  # receipt visible, optional
        self.assertEqual(validate_record(rec, self.cfg)["errors"], {})

    def test_missing_required(self):
        rec = {k: v for k, v in self.base.items() if k != "vendor"}
        rec["category"] = "Meals"
        self.assertIn("vendor", validate_record(rec, self.cfg)["errors"])

    def test_bad_enum_and_nested_number(self):
        rec = {**self.base, "category": "Banana",
               "line_items": [{"description": "x", "quantity": "abc",
                               "unit_price": 1}]}
        errs = validate_record(rec, self.cfg)["errors"]
        self.assertIn("category", errs)
        self.assertIn("line_items[0].quantity", errs)

    def test_is_visible_helper(self):
        f = {"visible_when": {"field": "category", "equals": "Equipment"}}
        self.assertTrue(is_visible(f, {"category": "Equipment"}))
        self.assertFalse(is_visible(f, {"category": "Meals"}))


class TestReconcile(unittest.TestCase):
    def setUp(self):
        self.cfg = load_config("expense_report")

    def test_unknown_field_dropped_and_warned(self):
        r = reconcile({"made_up": 1}, self.cfg)
        self.assertNotIn("made_up", r["prefilled"])
        self.assertTrue(any("made_up" in w for w in r["warnings"]))

    def test_enum_case_insensitive(self):
        r = reconcile({"category": "equipment"}, self.cfg)
        self.assertEqual(r["prefilled"]["category"], "Equipment")

    def test_enum_no_match_blanked(self):
        r = reconcile({"category": "Snacks"}, self.cfg)
        self.assertNotIn("category", r["prefilled"])
        self.assertTrue(r["warnings"])

    def test_dollar_string_coerced_in_group(self):
        r = reconcile({"line_items": [{"quantity": "2", "unit_price": "$50"}]},
                      self.cfg)
        self.assertEqual(r["prefilled"]["line_items"][0]["unit_price"], 50.0)

    def test_word_number_blanked(self):
        r = reconcile({"line_items": [{"quantity": "three", "unit_price": "10"}]},
                      self.cfg)
        row = r["prefilled"]["line_items"][0]
        self.assertNotIn("quantity", row)
        self.assertEqual(row["unit_price"], 10.0)

    def test_non_dict_input(self):
        r = reconcile(["not", "a", "dict"], self.cfg)
        self.assertEqual(r["prefilled"], {})

    def test_derived_never_prefilled(self):
        r = reconcile({"total": 9999}, self.cfg)
        self.assertNotIn("total", r["prefilled"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
