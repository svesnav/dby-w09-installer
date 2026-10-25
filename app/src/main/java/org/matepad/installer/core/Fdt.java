package org.matepad.installer.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Flattened device tree: in-place edits of fixed-size properties. */
public final class Fdt {
    private static final int MAGIC = 0xD00DFEED;
    private static final int BEGIN_NODE = 1, END_NODE = 2, PROP = 3, NOP = 4, END = 9;

    private Fdt() {
    }

    /** Offsets of the values of every property with this name and length. */
    public static List<Integer> propertyOffsets(byte[] dtb, String name, int length) {
        ByteBuffer b = ByteBuffer.wrap(dtb);  // big-endian
        if (b.getInt(0) != MAGIC) throw new IllegalArgumentException("not a flattened device tree");
        int offStruct = b.getInt(8), offStrings = b.getInt(12), sizeStruct = b.getInt(36);
        byte[] want = name.getBytes(StandardCharsets.US_ASCII);
        List<Integer> found = new ArrayList<>();
        int pos = offStruct, end = offStruct + sizeStruct;
        while (pos < end) {
            int token = b.getInt(pos);
            pos += 4;
            if (token == BEGIN_NODE) {
                while (dtb[pos] != 0) pos++;
                pos = (pos + 1 + 3) & ~3;
            } else if (token == PROP) {
                int len = b.getInt(pos), nameOff = b.getInt(pos + 4);
                pos += 8;
                if (len == length && nameEquals(dtb, offStrings + nameOff, want)) found.add(pos);
                pos = (pos + len + 3) & ~3;
            } else if (token == END) {
                break;
            } else if (token != END_NODE && token != NOP) {
                throw new IllegalArgumentException("bad FDT token " + token + " at " + (pos - 4));
            }
        }
        return found;
    }

    /** Writes a Bluetooth address (AA:BB:CC:DD:EE:FF) into local-bd-address, little-endian. */
    public static void setBdAddress(byte[] dtb, String address) {
        byte[] mac = Mac.parse(address);
        List<Integer> offsets = propertyOffsets(dtb, "local-bd-address", 6);
        if (offsets.size() != 1)
            throw new IllegalArgumentException("expected one local-bd-address, found " + offsets.size());
        for (int i = 0; i < 6; i++) dtb[offsets.get(0) + i] = mac[5 - i];
    }

    private static boolean nameEquals(byte[] dtb, int off, byte[] want) {
        for (int i = 0; i < want.length; i++) if (dtb[off + i] != want[i]) return false;
        return dtb[off + want.length] == 0;
    }
}
