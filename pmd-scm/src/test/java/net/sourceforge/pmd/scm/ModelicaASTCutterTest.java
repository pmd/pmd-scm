package net.sourceforge.pmd.scm;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.modelica.ModelicaLanguageModule;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;

public class ModelicaASTCutterTest extends AbstractASTCutterTest {
    public ModelicaASTCutterTest() {
        super(new MinimizerLanguageModuleAdapter(new ModelicaLanguageModule()).getDefaultParser(),
                Charset.defaultCharset());
    }

    @Test
    public void testMetainfo() throws IOException {
        Node root = initializeFor(getClass().getResource("TestPackage.mo"));
        testRemoveOneByOne(root);
    }
}
