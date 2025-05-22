package com.hack.parser.solver.enhanced;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Enhanced method caller finder that handles interface implementations
 * and provides optimized call chain analysis.
 * <p>
 * Key improvements:
 * 1. Simplified architecture with single class handling all functionality
 * 2. Interface-to-implementation mapping for static analysis
 * 3. Enhanced method resolution for interface calls
 * 4. Optimized parsing and caching
 * 5. Better error handling and cycle detection
 */
public class EnhancedMethodCallerFinderOlder {

    private final JavaParser parser;
    private final String packagePrefix;

    public EnhancedMethodCallerFinderOlder(Path sourceRoot, String packagePrefix) {
        this.packagePrefix = packagePrefix;
        this.parser = createParser(sourceRoot);
    }

    private JavaParser createParser(Path sourceRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(),
                new JavaParserTypeSolver(sourceRoot.toFile())
        );
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);
        return new JavaParser(config);
    }

    /**
     * Main entry point to find and print caller chains for a method at a specific line
     */
    public void findCallerChains(Path sourceRoot, String fullyQualifiedClassName, int lineNumber) {
        try {
            // Parse all Java files
            Map<String, CompilationUnit> compilationUnits = parseAllJavaFiles(sourceRoot);

            // Find target method by line number
            MethodDeclaration targetMethod = findMethodByLine(fullyQualifiedClassName, lineNumber, compilationUnits);
            if (targetMethod == null) {
                System.err.println("No method found at line " + lineNumber + " in class " + fullyQualifiedClassName);
                return;
            }

            // Build interface implementation mapping
            Map<String, Set<String>> interfaceToImplementations = buildInterfaceImplementationMap(compilationUnits);

            // Build reverse call graph with interface resolution
            Map<String, Set<String>> reverseCallGraph = buildEnhancedReverseCallGraph(
                    compilationUnits, interfaceToImplementations);

            // Build signature to method mapping
            Map<String, MethodDeclaration> signatureToMethod = buildSignatureToMethodMap(compilationUnits);

            // Get target method signature
            String targetSignature = getMethodSignature(targetMethod);

            System.out.println("Finding caller chains for: " + targetSignature + "\n");

            // Find and print all caller chains
            Set<String> visited = new HashSet<>();
            List<String> currentPath = new ArrayList<>();
            currentPath.add(targetSignature);

            findAndPrintCallerChains(targetSignature, reverseCallGraph, currentPath,
                    visited, signatureToMethod);

        } catch (Exception e) {
            System.err.println("Error analyzing caller chains: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<String, CompilationUnit> parseAllJavaFiles(Path sourceRoot) throws IOException {
        Map<String, CompilationUnit> compilationUnits = new HashMap<>();

        Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        ParseResult<CompilationUnit> result = parser.parse(javaFile);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            compilationUnits.put(javaFile.toString(), result.getResult().get());
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to parse " + javaFile + ": " + e.getMessage());
                    }
                });

        return compilationUnits;
    }

    private MethodDeclaration findMethodByLine(String className, int lineNumber,
                                               Map<String, CompilationUnit> compilationUnits) {
        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty()) continue;

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fullClassName = pkg.get().getNameAsString() + "." + clazz.getNameAsString();
                if (fullClassName.equals(className)) {
                    for (MethodDeclaration method : clazz.getMethods()) {
                        if (method.getRange().isPresent() &&
                                method.getRange().get().begin.line <= lineNumber &&
                                method.getRange().get().end.line >= lineNumber) {
                            return method;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Build mapping from interface signatures to their implementation signatures
     */
    private Map<String, Set<String>> buildInterfaceImplementationMap(Map<String, CompilationUnit> compilationUnits) {
        Map<String, Set<String>> interfaceToImpls = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty() || !pkg.get().getNameAsString().startsWith(packagePrefix)) continue;

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!clazz.isInterface() && !clazz.getImplementedTypes().isEmpty()) {
                    // This is a class implementing interfaces
                    for (MethodDeclaration method : clazz.getMethods()) {
                        try {
                            String implSignature = getMethodSignature(method);

                            // Find corresponding interface methods
                            for (var implementedType : clazz.getImplementedTypes()) {
                                try {
                                    ResolvedReferenceTypeDeclaration resolvedInterface =
                                            implementedType.resolve().asReferenceType().getTypeDeclaration().get();

                                    // Look for matching method in interface
                                    String interfaceSignature = findInterfaceMethodSignature(
                                            resolvedInterface, method.getNameAsString(), method);

                                    if (interfaceSignature != null) {
                                        interfaceToImpls.computeIfAbsent(interfaceSignature, k -> new HashSet<>())
                                                .add(implSignature);
                                    }
                                } catch (Exception e) {
                                    // Skip if cannot resolve interface
                                }
                            }
                        } catch (Exception e) {
                            // Skip if cannot resolve method
                        }
                    }
                }
            }
        }

        return interfaceToImpls;
    }

    private String findInterfaceMethodSignature(ResolvedReferenceTypeDeclaration interfaceDecl,
                                                String methodName, MethodDeclaration implMethod) {
        try {
            ResolvedMethodDeclaration resolvedImpl = implMethod.resolve();

            for (ResolvedMethodDeclaration interfaceMethod : interfaceDecl.getDeclaredMethods()) {
                if (interfaceMethod.getName().equals(methodName) &&
                        interfaceMethod.getNumberOfParams() == resolvedImpl.getNumberOfParams()) {

                    // Check parameter types match
                    boolean paramsMatch = true;
                    for (int i = 0; i < interfaceMethod.getNumberOfParams(); i++) {
                        if (!interfaceMethod.getParam(i).getType().equals(resolvedImpl.getParam(i).getType())) {
                            paramsMatch = false;
                            break;
                        }
                    }

                    if (paramsMatch) {
                        return interfaceMethod.getQualifiedSignature();
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to basic signature matching
        }
        return null;
    }

    /**
     * Build enhanced reverse call graph that resolves interface calls to implementations
     */
    private Map<String, Set<String>> buildEnhancedReverseCallGraph(
            Map<String, CompilationUnit> compilationUnits,
            Map<String, Set<String>> interfaceToImplementations) {

        Map<String, Set<String>> reverseCallGraph = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty() || !pkg.get().getNameAsString().startsWith(packagePrefix)) continue;

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = pkg.get().getNameAsString() + "." + clazz.getNameAsString();
                if (!className.startsWith(packagePrefix)) continue;

                for (MethodDeclaration method : clazz.getMethods()) {
                    String callerSignature = getMethodSignature(method);

                    // Find all method calls in this method
                    for (MethodCallExpr callExpr : method.findAll(MethodCallExpr.class)) {
                        try {
                            ResolvedMethodDeclaration resolvedCallee = callExpr.resolve();
                            String calleeSignature = resolvedCallee.getQualifiedSignature();

                            // Add direct call relationship
                            reverseCallGraph.computeIfAbsent(calleeSignature, k -> new HashSet<>())
                                    .add(callerSignature);

                            // If this is an interface call, also add relationships to implementations
                            Set<String> implementations = interfaceToImplementations.get(calleeSignature);
                            if (implementations != null) {
                                for (String implSignature : implementations) {
                                    reverseCallGraph.computeIfAbsent(implSignature, k -> new HashSet<>())
                                            .add(callerSignature);
                                }
                            }

                        } catch (Exception e) {
                            // Skip unresolvable calls
                        }
                    }
                }
            }
        }

        return reverseCallGraph;
    }

    private Map<String, MethodDeclaration> buildSignatureToMethodMap(Map<String, CompilationUnit> compilationUnits) {
        Map<String, MethodDeclaration> signatureToMethod = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                try {
                    String signature = getMethodSignature(method);
                    signatureToMethod.put(signature, method);
                } catch (Exception e) {
                    // Skip unresolvable methods
                }
            }
        }

        return signatureToMethod;
    }

    private String getMethodSignature(MethodDeclaration method) {
        try {
            return method.resolve().getQualifiedSignature();
        } catch (Exception e) {
            // Fallback to simple signature
            return method.getDeclarationAsString(false, false, false);
        }
    }

    private void findAndPrintCallerChains(String currentSignature,
                                          Map<String, Set<String>> reverseCallGraph,
                                          List<String> currentPath,
                                          Set<String> visited,
                                          Map<String, MethodDeclaration> signatureToMethod) {

        Set<String> callers = reverseCallGraph.getOrDefault(currentSignature, Collections.emptySet());

        if (callers.isEmpty()) {
            // End of chain - print the complete path
            printCallerChain(currentPath, signatureToMethod);
            return;
        }

        for (String caller : callers) {
            if (visited.contains(caller)) {
                continue; // Avoid cycles
            }

            visited.add(caller);
            currentPath.add(caller);

            findAndPrintCallerChains(caller, reverseCallGraph, currentPath, visited, signatureToMethod);

            // Backtrack
            currentPath.remove(currentPath.size() - 1);
            visited.remove(caller);
        }
    }

    private void printCallerChain(List<String> path, Map<String, MethodDeclaration> signatureToMethod) {
        System.out.println("=== Caller Chain ===");

        // Print in reverse order (from caller to target)
        for (int i = path.size() - 1; i >= 0; i--) {
            String signature = path.get(i);
            MethodDeclaration method = signatureToMethod.get(signature);

            System.out.println("\n--- " + signature + " ---");
            if (method != null) {
                System.out.println(method.toString());
            } else {
                System.out.println("(Method source not found)");
            }
        }
        System.out.println("====================\n");
    }

    // Main method for testing
    public static void main(String[] args) {
        String fullyQualifiedClassName = "com.hack.parser.test.HelperImpl";
        int lineNumber = 15;
        Path sourceRoot = Paths.get("src/main/java");
        String packagePrefix = "com.hack.parser.test";

        EnhancedMethodCallerFinderOlder finder = new EnhancedMethodCallerFinderOlder(sourceRoot, packagePrefix);
        finder.findCallerChains(sourceRoot, fullyQualifiedClassName, lineNumber);
    }
}