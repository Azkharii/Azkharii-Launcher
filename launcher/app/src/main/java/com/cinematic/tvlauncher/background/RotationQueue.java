package com.cinematic.tvlauncher.background;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * RotationQueue — Manages photo rotation sequence.
 * Implements Annex C.2 requirement: randomized sequence every ~20s avoiding
 * displaying the same photograph consecutively when multiple images are available.
 */
public class RotationQueue {
    private final List<File> imageFiles = new ArrayList<>();
    private final Random random;
    private File lastImage = null;

    public RotationQueue() {
        this(new Random());
    }

    public RotationQueue(Random random) {
        this.random = random != null ? random : new Random();
    }

    public synchronized void setImages(List<File> files) {
        imageFiles.clear();
        if (files != null) {
            for (File f : files) {
                if (f != null && f.exists() && f.isFile() && isSupportedImageName(f.getName())) {
                    imageFiles.add(f);
                }
            }
        }
        lastImage = null;
    }

    public synchronized void addImage(File file) {
        if (file != null && file.exists() && file.isFile() && isSupportedImageName(file.getName())) {
            if (!imageFiles.contains(file)) {
                imageFiles.add(file);
            }
        }
    }

    /** Marks the file currently displayed so the next random choice avoids it. */
    public synchronized void markDisplayed(File file) {
        if (file != null && imageFiles.contains(file)) lastImage = file;
    }

    /** Removes a file from rotation without rebuilding the full queue. */
    public synchronized void removeImage(File file) {
        if (file == null) return;
        imageFiles.remove(file);
        if (file.equals(lastImage)) lastImage = null;
    }

    public synchronized int size() {
        return imageFiles.size();
    }

    public synchronized boolean isEmpty() {
        return imageFiles.isEmpty();
    }

    /**
     * Gets the next random image, guaranteeing no immediate consecutive duplicate
     * if the queue has 2 or more images.
     */
    public synchronized File getNextRandomImage() {
        if (imageFiles.isEmpty()) {
            return null;
        }

        if (imageFiles.size() == 1) {
            lastImage = imageFiles.get(0);
            return lastImage;
        }

        // Multiple images: choose one different from lastImage
        int attempts = 0;
        File selected = null;
        while (attempts < 20) {
            int index = random.nextInt(imageFiles.size());
            File candidate = imageFiles.get(index);
            if (!candidate.equals(lastImage)) {
                selected = candidate;
                break;
            }
            attempts++;
        }

        // Fallback if loop didn't find a different candidate
        if (selected == null) {
            for (File candidate : imageFiles) {
                if (!candidate.equals(lastImage)) {
                    selected = candidate;
                    break;
                }
            }
        }

        if (selected == null) {
            selected = imageFiles.get(0);
        }

        lastImage = selected;
        return selected;
    }

    public synchronized File getLastImage() {
        return lastImage;
    }

    /** Shared extension policy used by both rotation and local-file discovery. */
    public static boolean isSupportedImageName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }
}
