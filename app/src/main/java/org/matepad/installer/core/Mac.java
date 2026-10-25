package org.matepad.installer.core;

import java.util.Locale;

/** Wi-Fi and Bluetooth addresses as AA:BB:CC:DD:EE:FF. */
public final class Mac {
    private Mac() {
    }

    public static byte[] parse(String s) {
        String t = s.trim();
        if (!t.matches("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
            throw new IllegalArgumentException("not a MAC address: " + s);
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(t.substring(i * 3, i * 3 + 2), 16);
        return b;
    }

    /** Upper-case form, or null when the text is no usable (unicast, non-zero) address. */
    public static String normalize(String s) {
        if (s == null) return null;
        try {
            byte[] b = parse(s);
            boolean zero = true;
            for (byte x : b) zero &= x == 0;
            if (zero || (b[0] & 1) != 0) return null;
            return s.trim().toUpperCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
