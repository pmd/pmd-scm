/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.invariants;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A public interface provided by the {@link net.sourceforge.pmd.scm.SourceCodeMinimizer} to
 * {@link Invariant}.
 */
public interface InvariantOperations {
    /**
     * Test for syntactical validity of all input files.
     */
    boolean allInputsAreParseable() throws IOException;

    /**
     * Get names of <b>compiler</b> input files.
     */
    List<Path> getScratchFileNames();
}
