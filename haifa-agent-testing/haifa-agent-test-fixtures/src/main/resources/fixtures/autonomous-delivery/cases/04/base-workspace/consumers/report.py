from catalog import search


def matching_titles(items: list[dict], query: str) -> list[str]:
    return [item["title"] for item in search(items, query)]
