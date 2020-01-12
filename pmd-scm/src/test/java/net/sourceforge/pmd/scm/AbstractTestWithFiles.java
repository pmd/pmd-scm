/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import org.junit.After;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class AbstractTestWithFiles {
    private final List<Path> filesToRemove = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        for (Path file: filesToRemove) {
            Files.delete(file);
        }
        filesToRemove.clear();
    }

    protected Path removeAfterTest(Path path) {
        filesToRemove.add(path);
        return path;
    }


    protected Path copyToTemporaryFile(InputStream stream, String suffix) throws IOException {
        Path file = removeAfterTest(Files.createTempFile("pmd-test-", suffix));
        Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }
}
