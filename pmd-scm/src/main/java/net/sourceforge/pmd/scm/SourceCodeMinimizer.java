/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.pmd.lang.Parser;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.scm.invariants.Invariant;
import net.sourceforge.pmd.scm.invariants.InvariantOperations;
import net.sourceforge.pmd.scm.strategies.MinimizationStrategy;
import net.sourceforge.pmd.scm.strategies.MinimizerOperations;

public class SourceCodeMinimizer implements InvariantOperations, MinimizerOperations {
    private final static String DIGEST_ALGO = "MD5";

    private static final class ContinueException extends Exception { }

    private static final class ExitException extends Exception { }

    private final MessageDigest messageDigest;
    private final Set<BigInteger> knownHashes;
    private final MinimizerLanguage language;
    private final Invariant invariant;
    private final MinimizationStrategy strategy;
    private final List<ASTCutter> cutters;
    private List<Node> currentRoots;
    private List<SCMConfiguration.FileMapping> fileMappings;

    public SourceCodeMinimizer(SCMConfiguration configuration) throws IOException {
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance(DIGEST_ALGO);
        } catch (NoSuchAlgorithmException e) {
            System.err.println("Message digest " + DIGEST_ALGO + " not found. Execution may be less efficient.");
        }
        messageDigest = md;
        knownHashes = new HashSet<>();

        language = configuration.getLanguageHandler();
        Parser parser = language.getParser(configuration.getLanguageVersion());
        invariant = configuration.getInvariantCheckerConfig().createChecker();
        strategy = configuration.getStrategyConfig().createStrategy();

        Charset sourceCharset = configuration.getSourceCharset();
        cutters = new ArrayList<>();
        fileMappings = configuration.getFileMappings();
        for (SCMConfiguration.FileMapping mapping: fileMappings) {
            Files.copy(mapping.input, mapping.output, StandardCopyOption.REPLACE_EXISTING);
            ASTCutter cutter = new ASTCutter(parser, sourceCharset, mapping.output);
            cutters.add(cutter);
        }
        currentRoots = ASTCutter.commitAll(cutters);
    }

    private BigInteger hashAllInputsOrNull() throws IOException {
        if (messageDigest == null) {
            return null;
        }
        messageDigest.reset();
        for (ASTCutter cutter: cutters) {
            cutter.hashScratchFile(messageDigest);
        }
        return new BigInteger(1, messageDigest.digest());
    }

    @Override
    public boolean allInputsAreParseable() throws IOException {
        for (ASTCutter cutter: cutters) {
            if (!cutter.isScratchFileParseable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Path> getScratchFileNames() {
        List<Path> result = new ArrayList<>();
        for (SCMConfiguration.FileMapping mapping: fileMappings) {
            result.add(mapping.output);
        }
        return result;
    }

    @Override
    public NodeInformationProvider getNodeInformationProvider() {
        return language.getNodeInformationProvider();
    }

    /**
     * Check invariant and commit if succesful.
     *
     * @param throwOnSuccess If successfully committed, unwind stack with {@link ContinueException}
     * @return <code>false</code> if unsuccessful, <code>true</code> if successful and <code>throwOnSuccess == false</code>
     * @throws ContinueException If successful and <code>throwOnSuccess == true</code>
     */
    private boolean tryCommit(boolean throwOnSuccess) throws Exception {
        // first, skip if already tested this file set
        BigInteger hash = hashAllInputsOrNull();
        if (hash != null && knownHashes.contains(hash)) {
            return false;
        }
        knownHashes.add(hash);

        // then, check invariant
        if (!invariant.checkIsSatisfied()) {
            return false;
        }

        // now, invariant is satisfied
        List<Node> roots = ASTCutter.commitAll(cutters);
        if (roots == null) {
            return false;
        }
        currentRoots = roots;
        // and parsed OK, so unwinding...
        if (throwOnSuccess) {
            throw new ContinueException();
        }
        // ... or just returning
        return true;
    }

    @Override
    public void tryCleanup() throws Exception {
        tryCleanup(true);
    }

    public void tryCleanup(boolean throwOnSuccess) throws Exception {
        for (ASTCutter cutter: cutters) {
            cutter.writeCleanedUpSource();
        }
        tryCommit(throwOnSuccess);
    }

    private void removeNodesWithVerboseError(ASTCutter cutter, Collection<Node> nodesToRemove) throws IOException {
        // Give user some information when AST nodes turns out overlapping
        try {
            cutter.writeTrimmedSource(nodesToRemove);
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace(System.err);
            System.err.println("An error occurred while cutting off the following nodes:");
            for (Node node: nodesToRemove) {
                String line = cutter.getScratchFile().toString() + ":" + Helper.explainNode(node);
                System.err.println(line);
            }
            System.exit(1);
        }
    }

    private void writeTrimmedSources(Collection<Node> nodesToRemove) throws IOException {
        Set<Node> nodes = new HashSet<>(nodesToRemove);
        for (ASTCutter cutter : cutters) {
            Set<Node> currentNodesToRemove = new HashSet<>(cutter.getAllNodes());
            currentNodesToRemove.retainAll(nodes);
            removeNodesWithVerboseError(cutter, currentNodesToRemove);
            nodes.removeAll(currentNodesToRemove);
        }
        if (!nodes.isEmpty()) {
            System.err.println("WARNING: strategy tries to remove unknown nodes!");
        }
    }

    @Override
    public void tryRemoveNodes(Collection<Node> nodesToRemove) throws Exception {
        if (nodesToRemove.isEmpty()) {
            // take only strict subsets of the original source
            // to avoid infinite loops
            return;
        }
        writeTrimmedSources(nodesToRemove);
        tryCommit(true);
    }

    @Override
    public void tryRemoveMultipleVariants(Collection<Collection<Node>> variants) throws Exception {
        for (Collection<Node> variant: variants) {
            tryRemoveNodes(variant);
        }
    }

    @Override
    public void forceRemoveNodesAndExit(Collection<Node> nodesToRemove) throws Exception {
        writeTrimmedSources(nodesToRemove);
        throw new ExitException();
    }

    private int getTotalFileSize() {
        int result = 0;
        for (ASTCutter cutter: cutters) {
            result += (int) cutter.getScratchFile().toFile().length();
        }
        return result;
    }

    private int getTotalNodeCount() {
        int result = 0;
        for (Node root : currentRoots) {
            result += getNodeCount(root);
        }
        return result;
    }

    private int getNodeCount(Node subtree) {
        int result = 1;
        for (int i = 0; i < subtree.jjtGetNumChildren(); ++i) {
            result += getNodeCount(subtree.jjtGetChild(i));
        }
        return result;
    }

    private void printStats(String when, int originalSize, int originalNodeCount) {
        int totalSize = getTotalFileSize();
        int totalNodeCount = getTotalNodeCount();
        int pcSize = totalSize * 100 / originalSize;
        int pcNodes = totalNodeCount * 100 / originalNodeCount;
        System.out.println(when + ": size "
                + totalSize + " bytes (" + pcSize + "%), "
                + totalNodeCount + " nodes (" + pcNodes + "%)");
        System.out.flush();
    }

    Invariant getInvariant() {
        return invariant;
    }

    MinimizationStrategy getStrategy() {
        return strategy;
    }

    public void runMinimization() throws Exception {
        strategy.initialize(this);
        invariant.initialize(this);

        final int originalSize = getTotalFileSize();
        final int originalNodeCount = getTotalNodeCount();
        System.out.println("Original file(s): " + originalSize + " bytes, " + originalNodeCount + " nodes.");
        System.out.flush();

        tryCleanup(false);
        printStats("After initial white-space cleanup", originalSize, originalNodeCount);

        int passNumber = 0;
        boolean shouldContinue = true;
        while (shouldContinue) {
            passNumber += 1;
            boolean performCleanup = passNumber % 10 == 0;
            try {
                if (performCleanup) {
                    tryCleanup();
                } else {
                    strategy.performSinglePass(currentRoots);
                    shouldContinue = false;
                }
            } catch (ContinueException ex) {
                shouldContinue = true;
            } catch (ExitException ex) {
                shouldContinue = false;
            }

            String cleanupLabel = performCleanup ? " (white-space cleanup)" : "";

            printStats("After pass #" + passNumber + cleanupLabel, originalSize, originalNodeCount);
        }

        tryCleanup(false);
        printStats("After final white-space cleanup", originalSize, originalNodeCount);
        for (ASTCutter cutter : cutters) {
            cutter.writeWithoutEmptyLines();
            tryCommit(false);
        }
        printStats("After blank line clean up", originalSize, originalNodeCount);

        for (ASTCutter cutter : cutters) {
            cutter.rollbackChange(); // to the last committed state
            cutter.close();
        }

        invariant.printStatistics(System.out);
        strategy.printStatistics(System.out);
    }
}
