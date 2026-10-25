package org.matepad.installer.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.junit.Test;

public class CoreTest {
    private static byte[] pattern(int n, int mul, int mod) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * mul % mod);
        return b;
    }

    private static String sha256(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** Same inputs as AOSP mkbootimg (android10) --header_version 2, which gave this hash. */
    @Test
    public void bootImageMatchesMkbootimg() throws Exception {
        BootImage img = new BootImage();
        img.pageSize = 4096;
        img.headerVersion = 2;
        img.kernelAddr = 0x8000;
        img.ramdiskAddr = 0x2000000;
        img.secondAddr = 0xf00000;
        img.tagsAddr = 0x100;
        img.dtbAddr = 0x1f00000;
        img.osVersion = (((11 << 14) | (0 << 7) | 0) << 11) | (((2020 - 2000) << 4) | 11);
        img.kernel = pattern(10000, 7, 251);
        img.ramdisk = pattern(5000, 3, 253);
        img.dtb = pattern(3000, 1, 241);
        img.setCmdline("console=tty0 rdinit=/init");
        byte[] built = img.build();
        assertEquals(28672, built.length);
        assertEquals("eae1f29e31e617ffbede2f7e3c393d68f10b7229fe872935906aea4d505aad36", sha256(built));

        BootImage back = BootImage.parse(built);
        assertEquals("console=tty0 rdinit=/init", back.getCmdline());
        assertArrayEquals(img.kernel, back.kernel);
        assertArrayEquals(img.dtb, back.dtb);
        assertEquals(built.length, BootImage.imageSize(built));
        assertArrayEquals(built, back.build());
    }

    @Test
    public void mbrHasOneLinuxPartition() {
        byte[] mbr = Partitions.mbr(249737216L);
        ByteBuffer b = ByteBuffer.wrap(mbr).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x83, mbr[446 + 4] & 0xff);
        assertEquals(8192, b.getInt(446 + 8));
        assertEquals(249737216 - 8192, b.getInt(446 + 12));
        assertEquals(0xAA55, b.getShort(510) & 0xffff);
        assertEquals(0, mbr[462 + 4]);
    }

    @Test
    public void ramdiskOverlayKeepsFooter() {
        byte[] stock = pattern(2 * 1024 * 1024, 5, 255);
        int n = stock.length;
        stock[n - 64] = 'A';
        stock[n - 63] = 'V';
        stock[n - 62] = 'B';
        stock[n - 61] = 'f';
        ByteBuffer.wrap(stock).putLong(n - 52, 600_000);
        byte[] payload = pattern(4096, 11, 251);
        byte[] out = Partitions.overlayRamdisk(stock, payload);
        assertEquals(payload[100], out[100]);
        assertEquals(0, out[599_999]);
        assertEquals(stock[600_000], out[600_000]);
        assertEquals(stock[n - 1], out[n - 1]);
    }

    @Test
    public void macs() {
        assertEquals("02:1A:2B:3C:4D:5E", Mac.normalize(" 02:1a:2b:3c:4d:5e\n"));
        assertNull(Mac.normalize("00:00:00:00:00:00"));
        assertNull(Mac.normalize("01:00:00:00:00:01"));
        assertNull(Mac.normalize("02-1A-2B-3C-4D-5E"));
    }
}
