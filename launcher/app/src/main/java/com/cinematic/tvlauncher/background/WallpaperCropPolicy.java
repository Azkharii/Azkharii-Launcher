package com.cinematic.tvlauncher.background;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Pure geometry policy for matching a wallpaper source to the launcher's center-cropped
 * viewport. Kept independent of Android graphics so aspect-ratio boundaries are testable.
 */
public final class WallpaperCropPolicy {

    private WallpaperCropPolicy() {}

    /**
     * Returns true unless this is positively identified as an opaque grayscale/RGB PNG.
     * Alpha-channel, palette, tRNS, malformed and unknown files remain ARGB_8888.
     */
    public static boolean hasTransparency(File file) {
        if (file == null || !file.isFile()) return true;
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            byte[] actual = new byte[8];
            input.readFully(actual);
            for (int i = 0; i < signature.length; i++) {
                if (actual[i] != signature[i]) return true;
            }
            int headerLength = input.readInt();
            byte[] headerType = new byte[4];
            input.readFully(headerType);
            if (headerLength != 13 || !isPngChunk(headerType, "IHDR")) return true;
            byte[] header = new byte[13];
            input.readFully(header);
            int colorType = header[9] & 0xFF;
            // Keep indexed/palette PNGs on ARGB_8888 conservatively.
            if (colorType != 0 && colorType != 2) return true;
            skipPngBytes(input, 4); // IHDR CRC
            while (true) {
                int length = input.readInt();
                if (length < 0) return true;
                byte[] chunkType = new byte[4];
                input.readFully(chunkType);
                if (isPngChunk(chunkType, "tRNS")) return true;
                if (isPngChunk(chunkType, "IDAT") || isPngChunk(chunkType, "IEND")) {
                    return false;
                }
                skipPngBytes(input, (long) length + 4L);
            }
        } catch (IOException | RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isPngChunk(byte[] type, String expected) {
        if (type == null || type.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            if ((type[i] & 0xFF) != expected.charAt(i)) return false;
        }
        return true;
    }

    private static void skipPngBytes(DataInputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            int skipped = input.skipBytes((int) Math.min(remaining, Integer.MAX_VALUE));
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("Unexpected end of PNG");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * Chooses a power-of-two sample for region decoding. A small 12.5% overshoot is accepted
     * so common 4K-to-2K cases can use half-resolution decode instead of retaining 4K pixels.
     */
    public static int calculateRegionSampleSize(int cropWidth, int cropHeight,
                                                int targetWidth, int targetHeight) {
        double widthRatio = Math.max(1, cropWidth) / (double) Math.max(1, targetWidth);
        double heightRatio = Math.max(1, cropHeight) / (double) Math.max(1, targetHeight);
        double ratio = Math.min(widthRatio, heightRatio);
        int sampleSize = 1;
        while (ratio >= sampleSize * 1.75d) {
            if (sampleSize > (1 << 28)) break;
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /** Returns [left, top, right, bottom] in source-image pixels. */
    public static int[] centerCropRect(int sourceWidth, int sourceHeight,
                                       int targetWidth, int targetHeight) {
        int width = Math.max(1, sourceWidth);
        int height = Math.max(1, sourceHeight);
        double targetAspect = Math.max(1, targetWidth) / (double) Math.max(1, targetHeight);
        double sourceAspect = width / (double) height;

        int left = 0;
        int top = 0;
        int right = width;
        int bottom = height;
        if (sourceAspect > targetAspect) {
            int cropWidth = Math.max(1, Math.min(width,
                    (int) Math.round(height * targetAspect)));
            left = (width - cropWidth) / 2;
            right = left + cropWidth;
        } else if (sourceAspect < targetAspect) {
            int cropHeight = Math.max(1, Math.min(height,
                    (int) Math.round(width / targetAspect)));
            top = (height - cropHeight) / 2;
            bottom = top + cropHeight;
        }
        return new int[] {left, top, right, bottom};
    }
}
