# Slug contract

`slug(value)` must trim surrounding whitespace, lowercase Unicode text, replace each run of non-letter/non-number
characters with one hyphen, and remove leading/trailing hyphens. Empty normalized output is rejected with `TypeError`.
Keep the public export and verify ordinary, Unicode, boundary and invalid input behavior.
