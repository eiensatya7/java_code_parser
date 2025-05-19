package com.hack.parser.solver;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.resolution.declarations.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class MethodCallerFinder {

    /**
     * Attaches JavaSymbolSolver to all CompilationUnits and finds all MethodCallExpr that resolve to the target method.
     *
     * @param targetMethod     the MethodDeclaration that we want to find calls to
     * @param sourceFolderPath the root path for source files (used to initialize the TypeSolver)
     * @param compilationUnits the map from file path to CompilationUnit (from Step 2)
     * @return a List of CallerInfo, each representing a calling MethodDeclaration and its containing class
     */
    public static List<CallerInfo> findCallers(
            MethodDeclaration targetMethod,
            Path sourceFolderPath,
            Map<String, CompilationUnit> compilationUnits
    ) {
        List<CallerInfo> callers = new ArrayList<>();

        // 1. Setup the TypeSolver to resolve symbols from the source root
        //    We use JavaParserTypeSolver pointing to the source folder so that all parsed CUs are available
        TypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(), // for resolving JDK classes, etc.
                new JavaParserTypeSolver(sourceFolderPath.toFile())
        );

        // 2. Attach symbol solver to the parser configuration
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration configWithSolver = new ParserConfiguration().setSymbolResolver(symbolSolver);
        JavaParser solverAwareParser = new JavaParser(configWithSolver);

        // 3. Re-parse each CompilationUnit with the new configuration (so that symbol information is attached)
        Map<String, CompilationUnit> resolvedCUs = new HashMap<>();
        compilationUnits.forEach((path, cu) -> {
            // Parse again with symbol solver enabled
            try {
                ParseResult<CompilationUnit> pr = solverAwareParser.parse(Paths.get(path));
                if (pr.isSuccessful() && pr.getResult().isPresent()) {
                    resolvedCUs.put(path, pr.getResult().get());
                }
            } catch (Exception e) {
                System.err.println("Re-parse failed for " + path + ": " + e.getMessage());
            }
        });

        // 4. Determine the ResolvedMethodDeclaration of the target for later comparison
        ResolvedMethodDeclaration resolvedTarget;
        try {
            resolvedTarget = targetMethod.resolve();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve target method: " + e.getMessage(), e);
        }

        // 5. Traverse all CUs, find MethodCallExpr nodes, and check if they resolve to the target
        resolvedCUs.forEach((path, cu) -> {
            // 1. Skip any CU whose package doesn't start with "com.hack.parser.test"
            Optional<PackageDeclaration> pkgDecl = cu.getPackageDeclaration();
            if (pkgDecl.isEmpty() ||
                    !pkgDecl.get().getNameAsString().startsWith("com.hack.parser.test")) {
                return; // skip resolvers for other packages
            }
            cu.findAll(MethodCallExpr.class).stream()
                    // 2a. Find the nearest ClassOrInterfaceDeclaration ancestor
                    .filter(methodCall -> {
                        Optional<ClassOrInterfaceDeclaration> enclosingClass =
                                methodCall.findAncestor(ClassOrInterfaceDeclaration.class);
                        if (enclosingClass.isEmpty()) {
                            return false; // ignore calls outside any class
                        }
                        // 2b. Get fully qualified name of that class (e.g., "com.hack.parser.test.TestSample")
                        Optional<String> fqn = enclosingClass.get().getFullyQualifiedName();
                        return fqn.isPresent()
                                && fqn.get().startsWith("com.hack.parser.test");
                    })
                    .forEach(methodCall -> {
                try {
                    ResolvedMethodDeclaration candidate = methodCall.resolve();
                    if (isSameMethod(candidate, resolvedTarget)) {
                        // Found a call to the target method
                        Optional<MethodDeclaration> enclosingMethod = methodCall.findAncestor(MethodDeclaration.class);
                        Optional<ClassOrInterfaceDeclaration> enclosingClass = methodCall.findAncestor(ClassOrInterfaceDeclaration.class);

                        if (enclosingMethod.isPresent() && enclosingClass.isPresent()) {
                            callers.add(new CallerInfo(
                                    enclosingClass.get().getFullyQualifiedName().orElse(""),
                                    enclosingMethod.get()
                            ));
                        }
                    }
                } catch (Exception ex) {
                    System.out.println("Failed to resolve method call: " + ex.getMessage());
                    // Some calls may fail to resolve; skip those
                }
            });
        });

        return callers;
    }

    /**
     * Utility to compare two ResolvedMethodDeclaration instances (signature, class, parameter types).
     */
    private static boolean isSameMethod(ResolvedMethodDeclaration a, ResolvedMethodDeclaration b) {
        // First check if they belong to the same type
        if (!a.getQualifiedSignature().equals(b.getQualifiedSignature())) {
            return false;
        }
        // If fully qualified signatures match, we consider them the same
        return true;
    }

    /**
     * Simple data holder for a method that calls the target, including its class name.
     */
    public static class CallerInfo {
        public final String callerClassName;
        public final MethodDeclaration callerMethod;

        public CallerInfo(String callerClassName, MethodDeclaration callerMethod) {
            this.callerClassName = callerClassName;
            this.callerMethod = callerMethod;
        }
    }
}
