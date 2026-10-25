package org.matepad.installer.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Android boot image, header v0 to v2 (the layout AOSP mkbootimg writes).
 *
 * The installer builds the Linux boot partition from the tablet's stock one:
 * every header field and the stock ramdisk stay, the kernel, DTB and command
 * line are replaced, and the image id is recomputed as mkbootimg does.
 */
public final class BootImage {
    public static final int V1_HEADER_SIZE = 1648;
    public static final int V2_HEADER_SIZE = 1660;
    private static final byte[] MAGIC = "ANDROID!".getBytes(StandardCharsets.US_ASCII);

    public int kernelAddr, ramdiskAddr, secondAddr, tagsAddr, pageSize, headerVersion, osVersion;
    public long recoveryDtboOffset, dtbAddr;
    public byte[] name = new byte[16];
    public byte[] kernel = new byte[0], ramdisk = new byte[0], second = new byte[0];
    public byte[] recoveryDtbo = new byte[0], dtb = new byte[0];
    private String cmdline = "";

    public static boolean isBootImage(byte[] data) {
        return data.length >= 8 && Arrays.equals(Arrays.copyOf(data, 8), MAGIC);
    }

    public static BootImage parse(byte[] data) {
        if (!isBootImage(data)) throw new IllegalArgumentException("not an Android boot image");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        BootImage img = new BootImage();
        b.position(8);
        int kernelSize = b.getInt();
        img.kernelAddr = b.getInt();
        int ramdiskSize = b.getInt();
        img.ramdiskAddr = b.getInt();
        int secondSize = b.getInt();
        img.secondAddr = b.getInt();
        img.tagsAddr = b.getInt();
        img.pageSize = b.getInt();
        img.headerVersion = b.getInt();
        img.osVersion = b.getInt();
        if (img.headerVersion > 2) throw new IllegalArgumentException("boot header v" + img.headerVersion + " not supported");
        if (img.pageSize < 2048 || Integer.bitCount(img.pageSize) != 1) throw new IllegalArgumentException("bad page size " + img.pageSize);
        b.get(img.name);
        byte[] cmd = new byte[512];
        b.get(cmd);
        b.position(b.position() + 32);  // id
        byte[] extra = new byte[1024];
        b.get(extra);
        img.cmdline = cString(cmd) + cString(extra);
        int recoveryDtboSize = 0, dtbSize = 0;
        if (img.headerVersion >= 1) {
            recoveryDtboSize = b.getInt();
            img.recoveryDtboOffset = b.getLong();
            b.getInt();  // header size
        }
        if (img.headerVersion >= 2) {
            dtbSize = b.getInt();
            img.dtbAddr = b.getLong();
        }
        int pos = img.pageSize;
        img.kernel = slice(data, pos, kernelSize);
        pos += img.pad(kernelSize);
        img.ramdisk = slice(data, pos, ramdiskSize);
        pos += img.pad(ramdiskSize);
        img.second = slice(data, pos, secondSize);
        pos += img.pad(secondSize);
        img.recoveryDtbo = slice(data, pos, recoveryDtboSize);
        pos += img.pad(recoveryDtboSize);
        img.dtb = slice(data, pos, dtbSize);
        return img;
    }

    /** Size of the image at the start of a partition dump, from its header. */
    public static int imageSize(byte[] head) {
        if (!isBootImage(head) || head.length < V2_HEADER_SIZE)
            throw new IllegalArgumentException("not an Android boot image");
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        BootImage img = new BootImage();
        img.pageSize = b.getInt(36);
        int version = b.getInt(40);
        int size = img.pageSize + img.pad(b.getInt(8)) + img.pad(b.getInt(16)) + img.pad(b.getInt(24));
        if (version >= 1) size += img.pad(b.getInt(1632));
        if (version >= 2) size += img.pad(b.getInt(1648));
        return size;
    }

    public String getCmdline() {
        return cmdline;
    }

    public void setCmdline(String cmdline) {
        if (cmdline.getBytes(StandardCharsets.US_ASCII).length > 512 + 1024 - 1)
            throw new IllegalArgumentException("command line too long");
        this.cmdline = cmdline;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN);
        h.put(MAGIC);
        h.putInt(kernel.length).putInt(kernelAddr);
        h.putInt(ramdisk.length).putInt(ramdisk.length > 0 ? ramdiskAddr : 0);
        h.putInt(second.length).putInt(second.length > 0 ? secondAddr : 0);
        h.putInt(tagsAddr).putInt(pageSize).putInt(headerVersion).putInt(osVersion);
        h.put(name);
        byte[] cmd = cmdline.getBytes(StandardCharsets.US_ASCII);
        h.put(Arrays.copyOf(cmd, 512));
        h.put(id());
        h.put(Arrays.copyOf(cmd.length > 512 ? Arrays.copyOfRange(cmd, 512, cmd.length) : new byte[0], 1024));
        if (headerVersion >= 1) {
            h.putInt(recoveryDtbo.length);
            h.putLong(recoveryDtbo.length > 0 ? recoveryDtboOffset : 0);
            h.putInt(headerVersion >= 2 ? V2_HEADER_SIZE : V1_HEADER_SIZE);
        }
        if (headerVersion >= 2) {
            h.putInt(dtb.length);
            h.putLong(dtbAddr);
        }
        out.write(h.array(), 0, pageSize);
        for (byte[] part : headerVersion >= 2 ? new byte[][]{kernel, ramdisk, second, recoveryDtbo, dtb}
                : headerVersion == 1 ? new byte[][]{kernel, ramdisk, second, recoveryDtbo}
                : new byte[][]{kernel, ramdisk, second}) {
            out.write(part, 0, part.length);
            out.write(new byte[pad(part.length) - part.length], 0, pad(part.length) - part.length);
        }
        return out.toByteArray();
    }

    /** SHA-1 over each section and its length, as mkbootimg's update_sha. */
    private byte[] id() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            byte[][] parts = headerVersion >= 2 ? new byte[][]{kernel, ramdisk, second, recoveryDtbo, dtb}
                    : headerVersion == 1 ? new byte[][]{kernel, ramdisk, second, recoveryDtbo}
                    : new byte[][]{kernel, ramdisk, second};
            for (byte[] p : parts) {
                sha.update(p);
                len.clear();
                sha.update(len.putInt(p.length).array());
            }
            return Arrays.copyOf(sha.digest(), 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int pad(int n) {
        return (n + pageSize - 1) / pageSize * pageSize;
    }

    private static byte[] slice(byte[] data, int off, int len) {
        if (off + len > data.length) throw new IllegalArgumentException("boot image truncated");
        return Arrays.copyOfRange(data, off, off + len);
    }

    private static String cString(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, StandardCharsets.US_ASCII);
    }
}
