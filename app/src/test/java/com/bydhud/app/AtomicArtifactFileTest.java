package com.bydhud.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class AtomicArtifactFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void publishesAndRepairsIncompleteOrWrongContent() throws Exception {
        File dir = temporary.newFolder();
        File target = new File(dir, "image.png");
        byte[] expected = {1, 2, 3, 4};
        AtomicArtifactFile.publish(target, expected);
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
        AtomicArtifactFile.publish(target, expected);
        Files.write(target.toPath(), new byte[]{1, 2});
        AtomicArtifactFile.publish(target, expected);
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
        Files.write(target.toPath(), new byte[]{4, 3, 2, 1});
        AtomicArtifactFile.publish(target, expected);
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"image.png"}, dir.list());
    }

    @Test public void failedPublicationPreservesDestinationAndCleansOwnTemporaryFile() throws Exception {
        File dir = temporary.newFolder();
        File target = new File(dir, "occupied");
        assertTrue(target.mkdir());
        Files.write(new File(target, "keep").toPath(), new byte[]{9});
        try {
            AtomicArtifactFile.publish(target, new byte[]{1, 2});
            fail("nonempty directory must not be replaced by an artifact");
        } catch (IOException expected) {
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(target, "keep").toPath()));
            assertArrayEquals(new String[]{"occupied"}, dir.list());
        }
    }
}
