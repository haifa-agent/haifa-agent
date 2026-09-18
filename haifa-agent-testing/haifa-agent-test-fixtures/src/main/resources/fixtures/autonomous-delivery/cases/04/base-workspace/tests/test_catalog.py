import unittest

from catalog import SearchRequest, search, search_v2
from consumers.report import matching_titles


ITEMS = [
    {"id": 1, "title": "Red Apple", "description": "fresh fruit", "tags": ["Food", "Fresh"]},
    {"id": 2, "title": "Green Tea", "description": "warm drink", "tags": ["Food", "Drink"]},
    {"id": 3, "title": "Notebook", "description": "red cover", "tags": ["Office"]},
]


class CatalogTest(unittest.TestCase):
    def test_old_api_remains_compatible(self):
        self.assertEqual([1, 3], [item["id"] for item in search(ITEMS, "RED")])

    def test_v2_filters_and_pages(self):
        result = search_v2(
            ITEMS,
            SearchRequest(query="", required_tags=("food",), offset=1, limit=1),
        )
        self.assertEqual(2, result.total)
        self.assertEqual((ITEMS[1],), result.items)

    def test_consumer_output_remains_list(self):
        self.assertEqual(["Red Apple", "Notebook"], matching_titles(ITEMS, "red"))

    def test_invalid_page_is_rejected(self):
        with self.assertRaises(ValueError):
            search_v2(ITEMS, SearchRequest(offset=-1))


if __name__ == "__main__":
    unittest.main()
