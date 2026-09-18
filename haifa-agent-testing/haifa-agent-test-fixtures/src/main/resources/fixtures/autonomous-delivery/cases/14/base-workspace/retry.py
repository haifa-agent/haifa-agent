def should_retry(status: int, attempt: int) -> bool:
    if status >= 400:
        return attempt < 3
    return False
