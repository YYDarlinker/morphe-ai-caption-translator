package app.yydarlinker.deepseekcaptions;

/** HTTP backoff in either delta seconds or an absolute date, never shortened before retry. */
final class RetryAfter {
    static long delayMs(String value, long now) {
        if (value == null) return 0;
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.max(0, Math.min(Long.MAX_VALUE / 1000, seconds)) * 1000;
        } catch (NumberFormatException date) {
            try {
                long at = java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(0, at - now);
            } catch (RuntimeException invalid) { return 0; }
        }
    }
    static long after(long now, long delay) { return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay; }
}
