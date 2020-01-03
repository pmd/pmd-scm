/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import net.sourceforge.pmd.lang.ast.Node;

import java.util.ArrayList;

final class Helper {
    private Helper() { }

    static String explainNode(Node node) {
        ArrayList<String> descriptions = new ArrayList<>();
        for (Node current = node; current != null; current = current.jjtGetParent()) {
            String xPathName = current.getXPathNodeName();
            int index = current.jjtGetChildIndex() + 1;
            String nameSuffix = (current.getImage() != null) ? (":" + current.getImage()) : "";
            descriptions.add(xPathName + "[" + index + nameSuffix + "]");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(node.getBeginLine());
        sb.append(":");
        sb.append(node.getBeginColumn());
        sb.append(": ");
        for (int i = descriptions.size() - 1; i >= 0; --i) {
            sb.append(descriptions.get(i));
            if (i > 0) {
                sb.append(" / ");
            }
        }
        return sb.toString();
    }
}
