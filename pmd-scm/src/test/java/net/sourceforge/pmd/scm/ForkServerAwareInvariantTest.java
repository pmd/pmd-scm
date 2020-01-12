/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ForkServerAwareInvariantTest extends AbstractTestWithFiles {
    private void performTesting(String command, String... invariantArgs) throws Exception {
        Assume.assumeTrue(SystemUtils.IS_OS_LINUX);

        SCMConfiguration configuration = new SCMConfiguration();
        Path inputFile = copyToTemporaryFile(getClass().getResourceAsStream("test-input.txt"), ".in");
        Path outputFile = removeAfterTest(Files.createTempFile("pmd-test-", ".out"));
        String cmdline = command + " " + outputFile.toString();

        String[] baseArgs = {
                "--language", "java",
                "--input-file", inputFile.toString(), "--output-file", outputFile.toString(),
                "--forkserver", "--command-line", cmdline,
                "--strategy", "greedy"
        };

        String[] args = ArrayUtils.addAll(baseArgs, invariantArgs);
        configuration.parse(args);
        Assert.assertNull(configuration.getErrorString());
        SourceCodeMinimizer minimizer = new SourceCodeMinimizer(configuration);
        minimizer.runMinimization();
        TestHelper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("greedy-test-retained-testRemoval.txt"), outputFile);
    }

    @Test
    public void testExitCode() throws Exception {
        performTesting("grep -q -F testRemoval", "--invariant", "exitcode", "--exact-return", "0");
    }

    @Test
    public void testPrintedMessage() throws Exception {
        performTesting("cat", "--invariant", "message", "--printed-message", "testRemoval");
    }
}
