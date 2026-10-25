package org.matepad.installer.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

/** Boot-partition payloads and the microSD partition table. */
public final class Partitions {
    /** The Linux partition starts at 4 MiB (SD erase-block alignment) and runs to the end of the card. */
    public static final long FIRST_SECTOR = 8192;

    private Partitions() {
    }

    /** MBR with one Linux partition (type 0x83) filling a card of this many 512-byte sectors. */
    public static byte[] mbr(long sectors) {
        if (sectors <= FIRST_SECTOR + 2048) throw new IllegalArgumentException("card too small");
        long count = Math.min(sectors - FIRST_SECTOR, 0xFFFFFFFFL);
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        byte[] signature = new byte[4];
        new SecureRandom().nextBytes(signature);
        b.position(440);
        b.put(signature);
        b.position(446);
        b.put((byte) 0x00).put((byte) 0xFE).put((byte) 0xFF).put((byte) 0xFF);  // not active, CHS unused
        b.put((byte) 0x83).put((byte) 0xFE).put((byte) 0xFF).put((byte) 0xFF);
        b.putInt((int) FIRST_SECTOR).putInt((int) count);
        b.position(510);
        b.put((byte) 0x55).put((byte) 0xAA);
        return b.array();
    }

    /** Bytes of the partition that mbr() creates on a card of this many sectors. */
    public static long partitionBytes(long sectors) {
        return Math.min(sectors - FIRST_SECTOR, 0xFFFFFFFFL) * 512;
    }

    /**
     * The ramdisk partition with this payload laid over its start: the rest up
     * to the AVB footer's original image size is zeroed, the footer stays
     * (linux-dby-w09 build_probe_images.sh does the same).
     */
    public static byte[] overlayRamdisk(byte[] stockPartition, byte[] payload) {
        int n = stockPartition.length;
        if (n < 4096 || stockPartition[n - 64] != 'A' || stockPartition[n - 63] != 'V'
                || stockPartition[n - 62] != 'B' || stockPartition[n - 61] != 'f')
            throw new IllegalArgumentException("ramdisk partition has no AVB footer");
        long size = ByteBuffer.wrap(stockPartition).getLong(n - 52);
        if (payload.length == 0 || payload.length > size || size > n - 64)
            throw new IllegalArgumentException("ramdisk payload of " + payload.length + " bytes does not fit " + size);
        byte[] out = stockPartition.clone();
        System.arraycopy(payload, 0, out, 0, payload.length);
        Arrays.fill(out, payload.length, (int) size, (byte) 0);
        return out;
    }

    /** Size of the DTBO table at the start of a dtbo partition dump. */
    public static int dtboSize(byte[] head) {
        ByteBuffer b = ByteBuffer.wrap(head);
        if (b.getInt(0) != 0xD7B7AB1E) throw new IllegalArgumentException("dtbo partition has no DTBO table");
        return b.getInt(4);
    }
}
