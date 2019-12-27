/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.strategies;

import net.sourceforge.pmd.scm.MinimizerLanguage;

/**
 * Factory for some specific implementation of {@link MinimizationStrategyConfiguration}.
 *
 * Such factories are expected to be registered in the SCM language support implementation entry points.
 *
 * @see MinimizerLanguage
 */
public interface MinimizationStrategyConfigurationFactory {
    /**
     * Get the short strategy identifier to be used on the command line.
     */
    String getName();

    /**
     * Creates fresh configuration object.
     *
     * Changes to objects returned by different invocations of this method
     * should not affect state of other such objects.
     */
    MinimizationStrategyConfiguration createConfiguration();
}
