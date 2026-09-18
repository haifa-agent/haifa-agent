# Catalog API v2 Contract

The existing `catalog.search(items, query)` API remains supported and returns
matching item dictionaries in input order.

Version 2 adds these public exports:

```python
SearchRequest(query="", required_tags=(), offset=0, limit=20)
SearchResult(items=(...), total=...)
search_v2(items, request) -> SearchResult
```

`SearchRequest` and `SearchResult` are immutable dataclasses.

- Query matching is case-insensitive against title and description.
- Every required tag must be present; tag matching is case-insensitive.
- Filtering preserves input order.
- `total` is the number before pagination.
- Offset is zero-based. Limit is from 1 through 100.
- Negative offsets, invalid limits, and non-`SearchRequest` requests raise
  `ValueError`.
- Input items and their nested tag lists are never mutated.
- Returned `items` is a tuple, but each item remains the original dictionary.

Both consumers under `consumers/` must use v2 internally while preserving their
documented command/function outputs.
