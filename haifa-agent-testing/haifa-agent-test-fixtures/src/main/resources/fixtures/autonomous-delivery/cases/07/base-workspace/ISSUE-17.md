# ISSUE-17: local-date activity view loses events near midnight

Users outside UTC report that the local-date activity view sometimes omits
events shortly after midnight and includes events from the next local day.
Support reproduced it in Asia/Shanghai. A New York customer reports the same
problem on the spring daylight-saving transition.

Stable API:

```java
WindowService.eventsForLocalDate(events, localDate, zoneId)
```

Expected behavior:

- interpret the requested date in the supplied `ZoneId`;
- use a half-open interval from that local day start to the next local day
  start, including exact start and excluding exact next-day start;
- handle 23-hour and 25-hour local days;
- return events ordered by `occurredAt`, then `id`;
- do not mutate the caller's list;
- return an unmodifiable result;
- reject null arguments with the normal JDK null contract.

Public class and method signatures must remain compatible.
