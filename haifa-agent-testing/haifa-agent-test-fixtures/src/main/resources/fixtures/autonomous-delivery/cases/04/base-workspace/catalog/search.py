def search(items: list[dict], query: str) -> list[dict]:
    needle = query.casefold()
    return [
        item
        for item in items
        if needle in item["title"].casefold()
        or needle in item.get("description", "").casefold()
    ]
