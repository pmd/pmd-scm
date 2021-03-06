/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.pmd.document.DeleteDocumentOperation;
import net.sourceforge.pmd.document.DocumentFile;
import net.sourceforge.pmd.document.DocumentOperationsApplierForNonOverlappingRegions;
import net.sourceforge.pmd.lang.Parser;
import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.ast.ParseException;

/**
 * A class for generating source files (as a plain text) from the <b>subset</b> of the given AST.
 *
 * The <b>expected</b> invariant is that the following trees should be equal:
 * <ul>
 *     <li>the original tree with every node marked for removal being deleted with its descending nodes</li>
 *     <li>the result of parsing of the plain-text file obtained from the corresponding subset</li>
 * </ul>
 *
 * In other words, for original source <code>TEXT</code> and <code>NODES</code> being a subset of all its AST nodes,
 * <code>parse(cut(TEXT, NODES)) == drop-recursively(parse(TEXT), NODES)</code>.
 *
 * This requirement can be slightly relaxed (such as not requiring presence of nodes that became empty).
 *
 * Please note, that this operation is <b>not required</b> to somehow retain formatting or create
 * nicely formatted files.
 */
public class ASTCutter implements AutoCloseable {
    private static final String WHITESPACE_CHARS = " \t";

    private final Path lastCommitted = Files.createTempFile("pmd-", ".tmp");
    private final Parser parser;
    private final Charset charset;

    private final Path scratchFile;
    private Node currentRoot;
    private final Set<Node> currentDocumentNodes = new HashSet<>();
    private final boolean validateNodes;

    /**
     * Create ASTCutter instance
     * @param parser        parser for the original and intermediate source files
     * @param charset       charset of source to be cut
     * @param scratchFile   file to be modified in-place
     * @param validateNodes silently ignore invalid nodes
     */
    public ASTCutter(Parser parser, Charset charset, Path scratchFile, boolean validateNodes) throws IOException {
        this.parser = parser;
        this.charset = charset;
        this.scratchFile = scratchFile;
        this.validateNodes = validateNodes;
    }

    public ASTCutter(Parser parser, Charset charset, Path scratchFile) throws IOException {
        this(parser, charset, scratchFile, true);
    }

    public Path getScratchFile() {
        return scratchFile;
    }

    public Set<Node> getAllNodes() {
        return Collections.unmodifiableSet(currentDocumentNodes);
    }

    /**
     * Converts list of AST {@link Node}s to be cut off into List of {@link DeleteDocumentOperation}s dealing with
     * the plain text file representation.
     *
     * @param treeRoot     the root of AST corresponding to the file being processed
     * @param deletedNodes the nodes marked for removal (all elements are expected to be accessible from the <code>treeRoot</code>)
     * @return a list of non-overlapping operations that, being applied on the file parsed as <code>treeRoot</code>,
     *         would generate a file that is parsed to <code>treeRoot</code> with all marked codes being cut off recursively.
     */
    private List<DeleteDocumentOperation> calculateTreeCutting(Node treeRoot, Collection<Node> deletedNodes) {
        ArrayList<DeleteDocumentOperation> result = new ArrayList<>();
        calculateTreeCutting(result, treeRoot, new HashSet<>(deletedNodes));
        return result;
    }

    /**
     * Validates node.
     *
     * For now, checks that the start position of the node is not after the end position.
     */
    private boolean nodeIsValid(Node node) {
        return node.getBeginLine() < node.getEndLine()
                || (node.getBeginLine() == node.getEndLine() && node.getBeginColumn() < node.getEndColumn());
    }

    private void calculateTreeCutting(List<DeleteDocumentOperation> result, Node node, Set<Node> deletedNodes) {
        // Technically, invalid node can lead to infinite loop when input hashing is off
        if (deletedNodes.contains(node) && (!validateNodes || nodeIsValid(node))) {
            // not recursing, deleting the whole range
            result.add(new DeleteDocumentOperation(
                    node.getBeginLine() - 1, node.getEndLine() - 1,
                    node.getBeginColumn() - 1, node.getEndColumn()));
        } else {
            for (int i = 0; i < node.jjtGetNumChildren(); ++i) {
                calculateTreeCutting(result, node.jjtGetChild(i), deletedNodes);
            }
        }
    }

    /**
     * Performs some conservative trimming of large parts of source code
     * not belonging to the AST (such as block comments).
     */
    private List<DeleteDocumentOperation> calculateTreeHolesTrimming() throws IOException {
        List<DeleteDocumentOperation> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(lastCommitted, charset);
        Node rootNode = load(lastCommitted);
        calculateTreeHolesTrimming(result, lines, rootNode, -1, 0, false);
        return result;
    }

    private void calculateTreeHolesTrimming(List<DeleteDocumentOperation> result, List<String> lines, Node node, final int prevEndLine, final int prevEndColumn, final boolean wasJustTrimmed) {
        final int curBeginLine = node.getBeginLine() - 1;
        final int curBeginColumn = node.getBeginColumn() - 1;

        boolean wasTrimmedHere = false;

        int curEndLine = prevEndLine;
        int curEndColumn = prevEndColumn;

        if (!wasJustTrimmed && prevEndLine < curBeginLine - 1 && (!validateNodes || nodeIsValid(node))) {
            // retain whitespace indentation to the left
            String curLine = lines.get(curBeginLine);
            int endDeleteLine = curBeginLine;
            int endDeleteColumn = curBeginColumn - 1;
            for (; endDeleteColumn >= 0 && WHITESPACE_CHARS.indexOf(curLine.charAt(endDeleteColumn)) != -1; --endDeleteColumn) {
                // nothing else
            }
            // are we retaining the whole current line (it is likely)
            if (endDeleteColumn == -1 && endDeleteLine > 0) {
                endDeleteLine -= 1;
                endDeleteColumn = lines.get(endDeleteLine).length();
            } else {
                endDeleteColumn += 1;
            }

            // check that end line of previous Node does not contain non-whitespace after end column
            boolean okToTrim = true;
            String prevLine = prevEndLine == -1 ? "" : lines.get(prevEndLine);
            for (int ind = prevEndColumn + 1; ind < prevLine.length(); ++ind) {
                if (WHITESPACE_CHARS.indexOf(prevLine.charAt(ind)) == -1) {
                    okToTrim = false;
                    break;
                }
            }
            if (okToTrim) {
                result.add(new DeleteDocumentOperation(prevEndLine + 1, endDeleteLine, 0, endDeleteColumn));
                curEndLine = endDeleteLine;
                curEndColumn = endDeleteColumn;
                wasTrimmedHere = true;
            }
        }


        for (int childInd = 0; childInd < node.jjtGetNumChildren(); ++childInd) {
            final Node child = node.jjtGetChild(childInd);

            calculateTreeHolesTrimming(result, lines, child, curEndLine, curEndColumn, childInd == 0 && (wasTrimmedHere || wasJustTrimmed));

            curEndLine = Math.max(curEndLine, child.getEndLine() - 1);
            curEndColumn = Math.max(curEndColumn, child.getEndColumn() - 1);
        }
    }

    /**
     * Checks that the input string contains only "allowed" chars
     *
     * @param text  A string to check
     * @param chars A string containing chars the checked string should be comprised of
     */
    private boolean allCharsFrom(String text, String chars) {
        for (int i = 0; i < text.length(); ++i) {
            if (chars.indexOf(text.charAt(i)) == -1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Trims lines of scratch file that contain only whitespace characters.
     */
    private void trimEmptyLinesInPlace(Path trimmedFile) throws IOException {
        List<String> lines = Files.readAllLines(trimmedFile, charset);
        try (BufferedWriter writer = Files.newBufferedWriter(trimmedFile, charset)) {
            for (String line : lines) {
                if (!allCharsFrom(line, WHITESPACE_CHARS)) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        }
    }

    /**
     * Populates set of nodes of current document with nodes from this subtree.
     */
    private void collectAllNodes(Node subtree) {
        currentDocumentNodes.add(subtree);
        for (int i = 0; i < subtree.jjtGetNumChildren(); ++i) {
            collectAllNodes(subtree.jjtGetChild(i));
        }
    }

    /**
     * Loads the specified file with the parser, does not change ASTCutter state.
     */
    private Node load(Path from) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(from, charset)) {
            return parser.parse(from.toString(), reader);
        }
    }

    public void hashScratchFile(MessageDigest md) throws IOException {
        byte[] bytes = Files.readAllBytes(scratchFile);
        md.update(bytes);
    }

    /**
     * Checks that current scratch file contents can be parsed by the current parser.
     *
     * It is generally a waste of time to spawn the entire compiler if even SCM cannot
     * parse current input. Especially, because we need to load it anyway if the invariant
     * would hold AND we want to proceed...
     */
    public boolean isScratchFileParseable() throws IOException {
        try {
            // result is unused
            load(scratchFile);
        } catch (ParseException ex) {
            return false;
        }
        return true;
    }

    // Root node prepared to be committed
    private Node preparedRoot;

    private void parseChanged() throws IOException {
        preparedRoot = load(scratchFile);
    }

    private Node commit() throws IOException {
        currentRoot = preparedRoot;
        Files.copy(scratchFile, lastCommitted, StandardCopyOption.REPLACE_EXISTING);

        currentDocumentNodes.clear();
        collectAllNodes(currentRoot);

        return currentRoot;
    }

    // Should be called after parseChanged() even if committed OK
    private void forgetParsed() {
        preparedRoot = null;
    }

    /**
     * Atomically commits changes to multiple files in the meaning
     * of the commitChange() method.
     */
    public static List<Node> commitAll(List<ASTCutter> cutters) throws IOException {
        List<Node> result = new ArrayList<>();
        try {
            for (ASTCutter cutter : cutters) {
                cutter.parseChanged();
            }
            // Either thrown, or everything was parsed OK
            for (ASTCutter cutter : cutters) {
                result.add(cutter.commit());
            }
            return result;
        } catch (ParseException ex) {
            return null;
        } finally {
            for (ASTCutter cutter : cutters) {
                cutter.forgetParsed();
            }
        }
    }

    /**
     * Accepts the last written file state as a new intermediate state.
     *
     * Please note, this does not anyhow relate to committing files under version control, if any.
     *
     * @return The root node of the "new current" source state or <code>null</code> if cannot parse
     */
    public Node commitChange() throws IOException {
        List<ASTCutter> args = new ArrayList<>();
        args.add(this);
        List<Node> result = commitAll(args);
        return result == null ? null : result.get(0);
    }

    /**
     * Rolls back intermediate file to the last <i>committed</i> state.
     */
    public void rollbackChange() throws IOException {
        Files.copy(lastCommitted, scratchFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private void deleteRegions(List<DeleteDocumentOperation> operations) throws IOException {
        try (DocumentFile document = new DocumentFile(scratchFile.toFile(), charset)) {
            DocumentOperationsApplierForNonOverlappingRegions applier = new DocumentOperationsApplierForNonOverlappingRegions(document);
            for (DeleteDocumentOperation operation : operations) {
                applier.addDocumentOperation(operation);
            }
            applier.apply();
        }
    }

    /**
     * Rolls back intermediate file, then tries to trim it once again.
     *
     * @param nodesToRemove nodes that have to be dropped from the resulting file together with their descendants.
     *                      They should be accessible from the root returned by the last <code>commitChange</code> call!
     */
    public void writeTrimmedSource(Collection<Node> nodesToRemove) throws IOException {
        rollbackChange();

        assert currentDocumentNodes.containsAll(nodesToRemove);

        deleteRegions(calculateTreeCutting(currentRoot, nodesToRemove));
    }

    public void writeCleanedUpSource() throws IOException {
        rollbackChange();
        deleteRegions(calculateTreeHolesTrimming());
    }

    public void writeWithoutEmptyLines() throws IOException {
        rollbackChange();
        trimEmptyLinesInPlace(scratchFile);
    }

    @Override
    public void close() throws Exception {
        Files.delete(lastCommitted);
    }
}
