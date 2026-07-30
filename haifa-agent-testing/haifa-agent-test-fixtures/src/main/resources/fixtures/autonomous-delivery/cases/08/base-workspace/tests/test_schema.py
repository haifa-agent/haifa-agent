import tempfile
import unittest
from pathlib import Path

from notesdb import open_database


class SchemaTest(unittest.TestCase):
    def test_new_database_is_v2(self):
        with tempfile.TemporaryDirectory() as temporary:
            connection = open_database(Path(temporary) / "notes.db")
            self.assertEqual(
                2, connection.execute("PRAGMA user_version").fetchone()[0]
            )
            columns = {
                row["name"] for row in connection.execute("PRAGMA table_info(notes)")
            }
            self.assertEqual(
                {"id", "body", "created_at", "status", "updated_at"}, columns
            )
            connection.close()

    def test_reopen_is_idempotent(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "notes.db"
            open_database(path).close()
            open_database(path).close()


if __name__ == "__main__":
    unittest.main()
