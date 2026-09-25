package com.leancore.chat.domain.util;

import com.leancore.chat.domain.exception.ValidationException;

public final class Texts {

    private Texts() {
    }

    /** Strips and checks 1..maxLength characters; the error message names the field. */
    public static String requireText(String value, String field, int maxLength) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw new ValidationException(field + " es obligatorio.");
        }
        if (stripped.length() > maxLength) {
            throw new ValidationException(field + " admite máximo " + maxLength + " caracteres.");
        }
        return stripped;
    }

    public static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Control characters other than newline and tab. */
    public static boolean hasControlChars(String value) {
        return value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t');
    }
}
