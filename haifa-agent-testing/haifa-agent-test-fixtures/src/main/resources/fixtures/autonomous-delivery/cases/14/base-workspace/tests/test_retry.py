import unittest

from retry import should_retry


class RetryTest(unittest.TestCase):
    def test_does_not_retry_client_errors(self):
        self.assertFalse(should_retry(400, 1))


if __name__ == "__main__":
    unittest.main()
