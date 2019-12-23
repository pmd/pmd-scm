# Source code minimizer (pmd-scm)

based on [PMD](https://pmd.github.io).

See [#2103 [core] Feature request: compiler-crashing test case minimization](https://github.com/pmd/pmd/issues/2103)
and [#2142 [core] Initial implementation of Source Code Minimizer](https://github.com/pmd/pmd/pull/2142)
for thoughts and discussions.

## Building

Just run

    ./mvnw clean verify

This will generate a binary distribution zip which contains all dependencies. The file is
in `pmd-scm-dist/target/pmd-scm-bin-1.0.0-SNAPSHOT.zip`. Just unzip it.

## Running

    bin/run.sh scm --language \
        --input-file pmd-core/src/main/java/net/sourceforge/pmd/scm/SCMConfiguration.java \
        --output-file test.java \
        --strategy xpath --xpath-expression "//Annotation"
