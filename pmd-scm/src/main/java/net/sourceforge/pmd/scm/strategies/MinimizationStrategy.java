/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.strategies;

import java.util.List;

import net.sourceforge.pmd.lang.ast.Node;

/**
 * What steps to perform to minimize the source.
 */
public interface MinimizationStrategy {
    /**
     * Called once before starting the minimization.
     */
    void initialize(MinimizerOperations ops);

    /**
     * Performs single minimization pass.
     */
    void performSinglePass(List<Node> roots) throws Exception;
}
