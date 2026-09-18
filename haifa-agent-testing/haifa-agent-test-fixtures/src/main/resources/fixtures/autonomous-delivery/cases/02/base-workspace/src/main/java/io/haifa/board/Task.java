package io.haifa.board;

import java.time.LocalDate;

record Task(long id, String title, Priority priority, LocalDate due, Status status) {
    enum Priority {
        LOW,
        MEDIUM,
        HIGH
    }

    enum Status {
        OPEN,
        DONE
    }

    Task markDone() {
        return new Task(id, title, priority, due, Status.DONE);
    }
}
