package com.leancore.chat.domain.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** RFC 4122 version 5 (SHA-1, name based) UUIDs: the same name always yields the same id. */
public final class NameBasedUuid {

    private static final UUID NAMESPACE = UUID.fromString("5f0c5b8e-4a0e-4c3b-9d7e-1c2a3b4c5d6e");

    private NameBasedUuid() {
    }

    public static UUID v5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(NAMESPACE));
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
                lsb = (lsb << 8) | (hash[i + 8] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
            bytes[i + 8] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return bytes;
    }
}
