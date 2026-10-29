package com.cinematic.tvlauncher;

import com.cinematic.tvlauncher.background.RotationQueue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for RotationQueue (Annex C.2).
 */
public class RotationQueueTest {

    public static void runTests() throws Exception {
        testEmptyQueue();
        testSingleItemQueue();
        testRandomRotationNoConsecutiveDuplicates();
        testFilterNonImageFiles();
        testMarkDisplayedAndRemoveImage();
        System.out.println("  [PASS] RotationQueueTest: all 5 tests passed.");
    }

    private static void testEmptyQueue() {
        RotationQueue queue = new RotationQueue();
        assert queue.isEmpty() : "Queue should be empty";
        assert queue.getNextRandomImage() == null : "Empty queue should return null";
    }

    private static void testSingleItemQueue() throws IOException {
        RotationQueue queue = new RotationQueue();
        File tempFile = File.createTempFile("photo1", ".jpg");
        tempFile.deleteOnExit();

        queue.addImage(tempFile);
        assert queue.size() == 1 : "Queue size should be 1";
        assert queue.getNextRandomImage().equals(tempFile) : "Single item queue must return the item";
        assert queue.getNextRandomImage().equals(tempFile) : "Repeated call on single item returns the item";
    }

    private static void testRandomRotationNoConsecutiveDuplicates() throws IOException {
        RotationQueue queue = new RotationQueue();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            File f = File.createTempFile("photo_" + i, ".png");
            f.deleteOnExit();
            files.add(f);
        }
        queue.setImages(files);
        assert queue.size() == 5 : "Queue size should be 5";

        File prev = null;
        for (int i = 0; i < 100; i++) {
            File current = queue.getNextRandomImage();
            assert current != null : "Item must not be null";
            if (prev != null) {
                assert !current.equals(prev) : "Immediate consecutive duplicate detected: " + current.getName() + " on iteration " + i;
            }
            prev = current;
        }
    }

    private static void testMarkDisplayedAndRemoveImage() throws IOException {
        RotationQueue queue = new RotationQueue();
        List<File> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            File file = File.createTempFile("managed_" + i, ".jpg");
            file.deleteOnExit();
            files.add(file);
        }
        queue.setImages(files);
        queue.markDisplayed(files.get(0));
        assert !queue.getNextRandomImage().equals(files.get(0))
                : "The currently displayed wallpaper should not immediately repeat";

        queue.removeImage(files.get(1));
        assert queue.size() == 2 : "Removing a wallpaper should update the rotation queue";
        for (int i = 0; i < 30; i++) {
            assert !queue.getNextRandomImage().equals(files.get(1))
                    : "Removed wallpaper must not be returned by rotation";
        }
    }

    private static void testFilterNonImageFiles() throws IOException {
        RotationQueue queue = new RotationQueue();
        File txtFile = File.createTempFile("document", ".txt");
        File pdfFile = File.createTempFile("document", ".pdf");
        File jpgFile = File.createTempFile("image", ".jpg");
        txtFile.deleteOnExit();
        pdfFile.deleteOnExit();
        jpgFile.deleteOnExit();

        queue.addImage(txtFile);
        queue.addImage(pdfFile);
        queue.addImage(jpgFile);

        assert queue.size() == 1 : "Only jpgFile should be added, actual size: " + queue.size();
        assert queue.getNextRandomImage().equals(jpgFile);
    }
}
