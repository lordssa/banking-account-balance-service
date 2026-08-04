package com.itau.account.domain;

import java.time.LocalDateTime;
import java.util.Objects;

public final class EventOrdering {
    private EventOrdering() {
    }

    public static int compare(LocalDateTime left, LocalDateTime right) {
        Objects.requireNonNull(left, "instante esquerdo é obrigatório");
        Objects.requireNonNull(right, "instante direito é obrigatório");
        return left.compareTo(right);
    }

    public static boolean isStrictlyNewer(LocalDateTime incoming, LocalDateTime current) {
        return compare(incoming, current) > 0;
    }

    public static boolean isEqualTimestamp(LocalDateTime left, LocalDateTime right) {
        return compare(left, right) == 0;
    }
}
