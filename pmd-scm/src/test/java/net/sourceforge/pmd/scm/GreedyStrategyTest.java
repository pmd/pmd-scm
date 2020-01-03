/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import net.sourceforge.pmd.scm.invariants.AbstractExternalProcessInvariant;
import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;
import org.junit.Test;

public class GreedyStrategyTest {
    private int getSpawnCount(SourceCodeMinimizer minimizer) {
        return ((AbstractExternalProcessInvariant) minimizer.getInvariant()).getSpawnCount();
    }

    private void testRetention(String textToRetain, int maxSpawns, String inputFileName, String referenceFileName) throws Exception {
        SCMConfiguration configuration = new SCMConfiguration();
        Path inputFile = TestHelper.copyToTemporaryFile(getClass().getResourceAsStream(inputFileName), ".in");
        Path outputFile = Files.createTempFile("pmd-test-", ".out");
        String cmdline;
        if (SystemUtils.IS_OS_WINDOWS) {
            cmdline = "type " + outputFile.toString();
        } else {
            cmdline = "cat " + outputFile.toString();
        }
        String[] args = {
            "--language", "java", "--input-file", inputFile.toString(), "--output-file", outputFile.toString(),
            "--invariant", "message", "--printed-message", textToRetain, "--command-line", cmdline,
            "--strategy", "greedy",
        };
        configuration.parse(args);
        Assert.assertNull(configuration.getErrorString());
        SourceCodeMinimizer minimizer = new SourceCodeMinimizer(configuration);
        minimizer.runMinimization();
        TestHelper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource(referenceFileName), outputFile);
        Assert.assertTrue(getSpawnCount(minimizer) <= maxSpawns);
    }

    @Test
    public void textRetentionTest() throws Exception {
        testRetention("testRemoval", 17, "test-input.txt", "greedy-test-retained-testRemoval.txt");
    }

    @Test
    public void performanceTest() throws Exception {
        // test that the strategy did not become too inefficient
        testRetention("Available languages:", 38, "greedy-large-input.txt", "greedy-large-output.txt");
    }

    @Test
    public void multiFileJavaMinimization() throws Exception {
        SCMConfiguration configuration = new SCMConfiguration();
        Path input1 = TestHelper.copyToTemporaryFile(getClass().getResourceAsStream("greedy-multifile-1.java"), ".java");
        Path input2 = TestHelper.copyToTemporaryFile(getClass().getResourceAsStream("greedy-multifile-2.java"), ".java");

        Path fileList = Files.createTempFile("pmd-test-file-list", ".txt");
        List<String> fileNames = new ArrayList<>();
        fileNames.add(input1.toString());
        fileNames.add(input2.toString());
        Files.write(fileList, fileNames, StandardOpenOption.WRITE);
        String[] args = {
                "--language", "java", "--input-file", "@" + fileList.toString(), "--output-file", "@" + fileList.toString(),
                "--invariant", "message", "--printed-message", "error: incompatible types: int cannot be converted to String",
                "--command-line", "javac " + input1.toString() + " " + input2.toString(),
                "--strategy", "greedy",
        };
        configuration.parse(args);
        Assert.assertNull(configuration.getErrorString());
        SourceCodeMinimizer minimizer = new SourceCodeMinimizer(configuration);
        minimizer.runMinimization();
        TestHelper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("greedy-multifile-1.out.java"), input1);
        TestHelper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("greedy-multifile-2.out.java"), input2);
        Assert.assertTrue(getSpawnCount(minimizer) <= 29);
    }
}
