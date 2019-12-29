/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.invariants;

import java.io.IOException;

import net.sourceforge.pmd.lang.Parser;

/**
 * A public interface provided by the {@link net.sourceforge.pmd.scm.SourceCodeMinimizer} to
 * {@link Invariant}.
 */
public interface InvariantOperations {
    /**
     * Test for syntactical validity of all input files.
     */
    boolean allInputsAreParseable() throws IOException;
}
