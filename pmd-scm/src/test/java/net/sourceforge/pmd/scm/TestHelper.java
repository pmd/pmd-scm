/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;

public final class TestHelper {
    private TestHelper() { }

    public static void assertResultedSourceEquals(Charset charset, URL expected, Path actual) throws IOException {
        String expectedContents = IOUtils.toString(expected, charset);
        String actualContents = new String(Files.readAllBytes(actual), charset);
        Assert.assertEquals(expectedContents, actualContents);
    }

    public static String printFileContentsCmd(String fileName) {
        if (SystemUtils.IS_OS_WINDOWS) {
            return "type " + fileName;
        } else {
            return "cat " + fileName;
        }
    }
}
