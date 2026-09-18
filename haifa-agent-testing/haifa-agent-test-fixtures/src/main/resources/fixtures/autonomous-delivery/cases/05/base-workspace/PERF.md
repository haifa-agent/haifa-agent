# topdupes production budget

`topdupes` reads newline-delimited values and reports repeated normalized
values. Production jobs contain 250,000 lines and 20,000–50,000 unique values.
On the reference laptop, processing must complete within 1.5 seconds after the
process starts and remain comfortably below 256 MiB.

Behavior is fixed:

- normalize with `strings.TrimSpace`;
- discard empty normalized values;
- comparisons remain case-sensitive;
- include only values occurring at least twice;
- order by count descending, then value ascending;
- return at most `limit` entries; non-positive limits return an empty result;
- support individual input lines up to 1 MiB;
- CLI output remains a JSON array of `{value,count}` objects.

Optimization must be based on input properties, not fixture-specific values.
