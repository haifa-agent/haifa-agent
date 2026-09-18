# Payment outcome contract

`submit_with_recovery(provider, request)` may receive:

- `SUCCEEDED`: return the receipt;
- `FAILED`: one policy retry is allowed;
- `UNKNOWN`: raise `OutcomeUnknown` immediately and never call the provider again.

The provider has no idempotency support, so replay after UNKNOWN could double-charge.
