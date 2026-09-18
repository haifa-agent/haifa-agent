package io.haifa.window;

import java.time.Instant;

public record Event(String id, Instant occurredAt) {}
