# Source code minimizer (pmd-scm)

[![Build Status](https://github.com/pmd/pmd-scm/workflows/build/badge.svg?branch=master)](https://github.com/pmd/pmd-scm/actions?query=workflow%3Abuild)

This is a tool based on [PMD](https://pmd.github.io). It is intended to minimize one or several source code files, while retaining some invariant (such as a specific compiler error message).

See
* [#2103 [core] Feature request: compiler-crashing test case minimization](https://github.com/pmd/pmd/issues/2103)
* [#2142 [core] Initial implementation of Source Code Minimizer](https://github.com/pmd/pmd/pull/2142)

for thoughts and discussions.

## Building

Just run

    ./mvnw clean verify

This will generate a binary distribution zip which contains all dependencies. The file is
in `pmd-scm-dist/target/pmd-scm-bin-1.0.0-SNAPSHOT.zip`. Just unzip it.

## Running

Drop all annotations from the source file:

    path/to/bin/run.sh scm --language java \
        --input-file pmd-scm/src/main/java/net/sourceforge/pmd/scm/SCMConfiguration.java \
        --output-file test.java \
        --strategy xpath --xpath-expression "//Annotation"

Let's look at more real-life example. Suppose, we have two files:

**TestResource-orig.java:**
```java
class TestResource {
    int func() {
        System.err.println("Hello World!");
        return 123;
    }

    void unused() {
        // unused
    }
}
```

**Main-orig.java:**
```java
class Main {
    public static void main(String[] args) {
        String str = new TestResource().func();

        return 123;
    }
}
```

If we try compiling it, we get:

    $ javac TestResource.java Main.java 
    Main.java:3: error: incompatible types: int cannot be converted to String
            String str = new TestResource().func();
                                                ^
    Main.java:5: error: incompatible types: unexpected return value
            return 123;
                   ^
    2 errors

Suppose, we want to minimize these two Java source files simultaneously, while retaining the invariant: the compiler should print

    error: incompatible types: int cannot be converted to String

Then, we could run

    $ path/to/bin/run.sh scm --language java \
          --input-file TestResource-orig.java Main-orig.java \
          --output-file TestResource.java Main.java \
          --invariant message --printed-message "error: incompatible types: int cannot be converted to String" \
          --command-line "javac TestResource.java Main.java" \
          --strategy greedy
    Original file(s): 290 bytes, 77 nodes.
    After initial white-space cleanup: size 258 bytes (88%), 77 nodes (100%)
    After pass #1: size 255 bytes (87%), 64 nodes (83%)
    After pass #2: size 244 bytes (84%), 57 nodes (74%)
    After pass #3: size 205 bytes (70%), 51 nodes (66%)
    After pass #4: size 192 bytes (66%), 46 nodes (59%)
    After pass #5: size 181 bytes (62%), 39 nodes (50%)
    After pass #6: size 179 bytes (61%), 39 nodes (50%)
    After final white-space cleanup: size 149 bytes (51%), 39 nodes (50%)
    After blank line clean up: size 147 bytes (50%), 39 nodes (50%)

Now, we get the following files:

**TestResource.java:**
```java
class TestResource {
    int func() {
    }
}
```

**Main.java:**
```java
class Main {
    public static void main() {
        String str = new TestResource().func();
    }
}
```

    $ javac TestResource.java Main.java 
    TestResource.java:3: error: missing return statement
        }
        ^
    Main.java:3: error: incompatible types: int cannot be converted to String
            String str = new TestResource().func();
                                                ^
    2 errors
