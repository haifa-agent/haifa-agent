import sqlite3
import tempfile
import unittest
from pathlib import Path

from migrate import migrate


class MigrationTest(unittest.TestCase):
    def test_migrates_version_one(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "jobs.db"
            connection = sqlite3.connect(path)
            connection.executescript(
                "CREATE TABLE jobs(id INTEGER PRIMARY KEY, name TEXT NOT NULL);"
                "INSERT INTO jobs VALUES(1,'one'); PRAGMA user_version=1;"
            )
            connection.close()
            migrate(str(path))
            connection = sqlite3.connect(path)
            self.assertEqual(2, connection.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual((1, "one", "pending"), connection.execute("SELECT * FROM jobs").fetchone())
            connection.close()


if __name__ == "__main__":
    unittest.main()
