package io.haifa.agent.execution.api;

import java.nio.charset.StandardCharsets;

/** Streaming, fixed-memory output retention that preserves both the beginning and the end. */
public final class BoundedOutputBuffer {
    private final int maximum;
    private final byte[] head;
    private final byte[] tail;
    private int headLength;
    private long count;

    public BoundedOutputBuffer(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
        this.head = new byte[(maximum + 1) / 2];
        this.tail = new byte[maximum / 2];
    }

    public void write(byte[] values) {
        write(values, 0, values.length);
    }

    public void write(byte[] values, int offset, int length) {
        if (values == null) throw new NullPointerException("values must not be null");
        if (offset < 0 || length < 0 || offset + length > values.length) {
            throw new IndexOutOfBoundsException("invalid output slice");
        }
        for (int index = offset; index < offset + length; index++) {
            if (headLength < head.length) {
                head[headLength++] = values[index];
            } else if (tail.length > 0) {
                tail[(int) ((count - head.length) % tail.length)] = values[index];
            }
            count++;
        }
    }

    public byte[] bytes() {
        if (!truncated()) {
            byte[] result = new byte[(int) count];
            System.arraycopy(head, 0, result, 0, headLength);
            if (count > headLength) {
                System.arraycopy(tail, 0, result, headLength, (int) count - headLength);
            }
            return result;
        }
        long omitted = omittedBytes();
        byte[] marker = omissionMarker(omitted);
        for (int attempts = 0; attempts < 3; attempts++) {
            long exact = count - Math.max(0, maximum - marker.length);
            if (exact == omitted) break;
            omitted = exact;
            marker = omissionMarker(omitted);
        }
        if (marker.length >= maximum) {
            byte[] result = new byte[maximum];
            System.arraycopy(marker, 0, result, 0, maximum);
            return result;
        }
        int contentBudget = Math.max(0, maximum - marker.length);
        int headBudget = Math.min(headLength, (contentBudget + 1) / 2);
        int tailBudget = Math.min(tail.length, contentBudget - headBudget);
        byte[] result = new byte[headBudget + marker.length + tailBudget];
        System.arraycopy(head, 0, result, 0, headBudget);
        System.arraycopy(marker, 0, result, headBudget, marker.length);
        if (tailBudget > 0) {
            int available = (int) Math.min(count - head.length, tail.length);
            int start = count - head.length <= tail.length ? 0 : (int) ((count - head.length) % tail.length);
            int skip = available - tailBudget;
            int logicalStart = (start + skip) % tail.length;
            int first = Math.min(tailBudget, tail.length - logicalStart);
            System.arraycopy(tail, logicalStart, result, headBudget + marker.length, first);
            if (first < tailBudget) {
                System.arraycopy(tail, 0, result, headBudget + marker.length + first, tailBudget - first);
            }
        }
        return result;
    }

    public long byteCount() {
        return count;
    }

    public long omittedBytes() {
        return Math.max(0, count - maximum);
    }

    public boolean truncated() {
        return count > maximum;
    }

    private static byte[] omissionMarker(long omitted) {
        return ("\n... " + omitted + " bytes omitted ...\n").getBytes(StandardCharsets.UTF_8);
    }
}
