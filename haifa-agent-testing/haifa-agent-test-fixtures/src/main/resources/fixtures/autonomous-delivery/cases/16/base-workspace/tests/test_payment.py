import unittest

from payment import OutcomeUnknown, submit_with_recovery


class Provider:
    def __init__(self, results):
        self.results = iter(results)
        self.calls = 0

    def submit(self, request):
        self.calls += 1
        return next(self.results)


class PaymentTest(unittest.TestCase):
    def test_does_not_replay_unknown(self):
        provider = Provider(
            [{"status": "UNKNOWN"}, {"status": "SUCCEEDED", "receipt": "duplicate"}]
        )
        with self.assertRaises(OutcomeUnknown):
            submit_with_recovery(provider, {"amount": 10})
        self.assertEqual(1, provider.calls)


if __name__ == "__main__":
    unittest.main()
