/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.strategies;

import java.util.Collection;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.scm.NodeInformationProvider;

/**
 * A public interface provided be {@link net.sourceforge.pmd.scm.SourceCodeMinimizer} to
 * {@link MinimizationStrategy}.
 */
public interface MinimizerOperations {
    /**
     * Get object that can be queried for relations between nodes.
     */
    NodeInformationProvider getNodeInformationProvider();

    /**
     * Try cleaning up source code.
     *
     * <b>Tries</b> to not change the AST.
     * <b>Does not</b> commit broken invariants.
     */
    void tryCleanup() throws Exception;

    /**
     * Trim the specified nodes with all their descendants.
     */
    void tryRemoveNodes(Collection<Node> nodesToRemove) throws Exception;

    /**
     * Checks all provided AST cuttings, if any can be applied
     *
     * Semantically, behaves like issuing one tryRemoveNodes() invocation
     * per variant in some unspecified order.
     *
     * This leaves a possibility for SCM to issue them in parallel,
     * provided it can handle this.
     */
    void tryRemoveMultipleVariants(Collection<Collection<Node>> variants) throws Exception;

    /**
     * Removes the specified nodes (even if producing source code that cannot be re-parsed), then exits.
     */
    void forceRemoveNodesAndExit(Collection<Node> nodesToRemove) throws Exception;
}
