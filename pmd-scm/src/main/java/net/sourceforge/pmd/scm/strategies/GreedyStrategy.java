/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.strategies;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sourceforge.pmd.lang.ast.Node;

/**
 * A simple interactive (in a sense, compiler-driven) greedy strategy that tries to cut off
 * different AST subtrees with depending nodes until it cannot make any step.
 */
public class GreedyStrategy extends AbstractMinimizationStrategy {
    public static class Configuration extends AbstractConfiguration {
        @Override
        public MinimizationStrategy createStrategy() {
            return new GreedyStrategy(this);
        }
    }

    public static final MinimizationStrategyConfigurationFactory FACTORY = new AbstractFactory("greedy") {
        @Override
        public MinimizationStrategyConfiguration createConfiguration() {
            return new Configuration();
        }
    };

    private GreedyStrategy(Configuration configuration) {
        super(configuration);
    }

    private final Map<Node, HashSet<Node>> directlyDependingNodes = new HashMap<>();
    private final Map<Node, Set<Node>> transitivelyDependingNodes = new HashMap<>();

    private void fetchDirectDependentsFromSubtree(Node node) {
        // process depending nodes
        if (!directlyDependingNodes.containsKey(node)) {
            directlyDependingNodes.put(node, new HashSet<Node>());
        }
        directlyDependingNodes.get(node).addAll(ops.getNodeInformationProvider().getDirectlyDependingNodes(node));

        // process dependencies
        Set<Node> dependencies = ops.getNodeInformationProvider().getDirectDependencies(node);
        for (Node dependency: dependencies) {
            if (!directlyDependingNodes.containsKey(dependency)) {
                directlyDependingNodes.put(dependency, new HashSet<Node>());
            }
            directlyDependingNodes.get(dependency).add(node);
        }

        // recurse
        for (int i = 0; i < node.jjtGetNumChildren(); ++i) {
            fetchDirectDependentsFromSubtree(node.jjtGetChild(i));
        }
    }

    /**
     * This method implements Depth-First Search.
     *
     * Vertex state is determined by the <code>transitivelyDependingNodes.get(node)</code>:
     * <ul>
     *     <li><code>null</code> means this vertex is being visited for the first time</li>
     *     <li>empty set means this vertex was visited before (and one should check its directly dependent vertices)</li>
     *     <li>non-empty set means this vertex is fully processed</li>
     * </ul>
     */
    private Set<Node> indirectlyDependentNodesFor(Node currentNode) {
        final Set<Node> oldValue = transitivelyDependingNodes.get(currentNode);
        if (oldValue == null) {
            // mark this node as entered
            transitivelyDependingNodes.put(currentNode, new HashSet<Node>());

            // create separate set for ongoing calculation, see vertex state
            final HashSet<Node> calculated = new HashSet<>();
            // recurse
            final HashSet<Node> directlyDepending = directlyDependingNodes.get(currentNode);
            for (Node dependingNode: directlyDepending) {
                calculated.addAll(indirectlyDependentNodesFor(dependingNode));
            }
            calculated.add(currentNode);

            // finally, put real result to map
            transitivelyDependingNodes.put(currentNode, Collections.unmodifiableSet(calculated));

            return calculated;
        } else {
            // in other two cases no need to do anything
            return oldValue;
        }
    }

    private void collectNodesToRemove(Set<Node> result, Node node) {
        result.addAll(indirectlyDependentNodesFor(node));
        for (int i = 0; i < node.jjtGetNumChildren(); ++i) {
            collectNodesToRemove(result, node.jjtGetChild(i));
        }
    }

    private int previousPosition;
    private int positionCountdown;
    private int restartCount;

    private void tryRemoveAt(Node currentNode) throws Exception {
        List<Collection<Node>> variants = new ArrayList<>();

        if (currentNode.jjtGetParent() != null) {
            // try dropping this node, if this is not the AST root
            Set<Node> toRemoveWithThis = new HashSet<>();
            collectNodesToRemove(toRemoveWithThis, currentNode);
            variants.add(toRemoveWithThis);
        }

        // try removing first or second half
        Set<Node> toRemoveFirstHalf = new HashSet<>();
        Set<Node> toRemoveSecondHalf = new HashSet<>();
        for (int i = 0; i < currentNode.jjtGetNumChildren(); ++i) {
            if (i < currentNode.jjtGetNumChildren() / 2) {
                collectNodesToRemove(toRemoveFirstHalf, currentNode.jjtGetChild(i));
            } else {
                collectNodesToRemove(toRemoveSecondHalf, currentNode.jjtGetChild(i));
            }
        }
        variants.add(toRemoveFirstHalf);
        variants.add(toRemoveSecondHalf);

        ops.tryRemoveMultipleVariants(variants);
    }

    /**
     * Traverse the passed subtree until successfully removing something.
     *
     * @see net.sourceforge.pmd.scm.SourceCodeMinimizer.ContinueException
     */
    private void findNodeToRemove(Node currentNode) throws Exception {
        previousPosition += 1;
        positionCountdown -= 1;
        // It is supposed to be balanced, so that restarted right from the next node.
        // It was observed that off-by-one error here ("<" vs. "<=") can make minimizing Java source
        // take 3x times more/less! But this can depend on the particular source
        // or programming language...
        // TODO will be mis-positioned if some dependent nodes are before the node itself
        if (positionCountdown <= 0) {
            tryRemoveAt(currentNode);
            // if exception was not thrown, then removal was not successful
        }

        for (int i = 0; i < currentNode.jjtGetNumChildren(); ++i) {
            findNodeToRemove(currentNode.jjtGetChild(i));
        }
    }

    @Override
    public void performSinglePass(List<Node> roots) throws Exception {
        positionCountdown = previousPosition;
        previousPosition = 0;
        directlyDependingNodes.clear();
        transitivelyDependingNodes.clear();
        for (Node root : roots) {
            fetchDirectDependentsFromSubtree(root);
        }
        for (Node currentRoot : roots) {
            findNodeToRemove(currentRoot);
        }
        // If we are here, then fast restart logic failed.
        // Trying to restart from scratch...
        previousPosition = 0;
        positionCountdown = 0;
        restartCount += 1;
        for (Node currentRoot : roots) {
            findNodeToRemove(currentRoot);
        }
    }

    @Override
    public void printStatistics(PrintStream stream) {
        stream.println("Greedy strategy restart count: " + restartCount);
    }
}
