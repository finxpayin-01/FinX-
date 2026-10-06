package com.finx.banking.dto;

import java.time.Instant;

public record BankStatementQuery(
        String providerAccountId,
        Instant windowStart,
        Instant windowEnd,
        String cursor,
        int pageSize
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String providerAccountId;
        private Instant windowStart;
        private Instant windowEnd;
        private String cursor;
        private int pageSize = 50;

        public Builder providerAccountId(String providerAccountId) {
            this.providerAccountId = providerAccountId;
            return this;
        }

        public Builder windowStart(Instant windowStart) {
            this.windowStart = windowStart;
            return this;
        }

        public Builder windowEnd(Instant windowEnd) {
            this.windowEnd = windowEnd;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public BankStatementQuery build() {
            return new BankStatementQuery(providerAccountId, windowStart, windowEnd, cursor, pageSize);
        }
    }

    public String getProviderAccountId() { return providerAccountId(); }
    public Instant getWindowStart() { return windowStart(); }
    public Instant getWindowEnd() { return windowEnd(); }
    public String getCursor() { return cursor(); }
    public int getPageSize() { return pageSize(); }
}
