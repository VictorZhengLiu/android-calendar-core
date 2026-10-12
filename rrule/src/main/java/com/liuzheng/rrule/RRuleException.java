package com.liuzheng.rrule;

/**
 * Thrown when an RRULE string cannot be parsed, or when a parsed rule is used in an
 * unsupported way.
 *
 * <p>This is an unchecked exception: a malformed RRULE is a programming/data error, not a
 * condition callers are expected to recover from on every call.
 */
public class RRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RRuleException(String message) {
        super(message);
    }

    public RRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
