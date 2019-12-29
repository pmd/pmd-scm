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

import org.apache.commons.lang3.SystemUtils;
import org.junit.Assert;
import org.junit.Test;

public class GreedyStrategyTest {
    @Test
    public void textRetentionTest() throws Exception {
        SCMConfiguration configuration = new SCMConfiguration();
        Path inputFile = Helper.copyToTemporaryFile(getClass().getResourceAsStream("cutter-test-input.txt"), ".in");
        Path outputFile = Files.createTempFile("pmd-test-", ".out");
        String cmdline;
        if (SystemUtils.IS_OS_WINDOWS) {
            cmdline = "type " + outputFile.toString();
        } else {
            cmdline = "cat " + outputFile.toString();
        }
        String[] args = {
            "--language", "java", "--input-file", inputFile.toString(), "--output-file", outputFile.toString(),
            "--invariant", "message", "--printed-message", "testRemoval", "--command-line", cmdline,
            "--strategy", "greedy",
        };
        configuration.parse(args);
        Assert.assertNull(configuration.getErrorString());
        SourceCodeMinimizer minimizer = new SourceCodeMinimizer(configuration);
        minimizer.runMinimization();
        Helper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("cutter-test-retained-testRemoval.txt"), outputFile);
    }

    @Test
    public void multiFileJavaMinimization() throws Exception {
        SCMConfiguration configuration = new SCMConfiguration();
        Path input1 = Helper.copyToTemporaryFile(getClass().getResourceAsStream("greedy-multifile-1.java"), ".java");
        Path input2 = Helper.copyToTemporaryFile(getClass().getResourceAsStream("greedy-multifile-2.java"), ".java");

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
        Helper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("greedy-multifile-1.out.java"), input1);
        Helper.assertResultedSourceEquals(StandardCharsets.UTF_8, getClass().getResource("greedy-multifile-2.out.java"), input2);
    }
}
