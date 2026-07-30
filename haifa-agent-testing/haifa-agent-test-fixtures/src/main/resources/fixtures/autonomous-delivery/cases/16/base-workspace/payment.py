class OutcomeUnknown(RuntimeError):
    pass


def submit_with_recovery(provider, request):
    for _ in range(2):
        result = provider.submit(request)
        if result["status"] == "SUCCEEDED":
            return result["receipt"]
        if result["status"] in {"FAILED", "UNKNOWN"}:
            continue
    raise RuntimeError("payment failed")
