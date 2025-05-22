package com.hack.parser.solver.fixed;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class RecursiveCallerFinder {



    // 1. Parse all Java files under sourceRoot into symbol-resolved CompilationUnits.
    public static Map<String, CompilationUnit> parseAllJavaFiles(Path sourceRoot) throws IOException {
        Map<String, CompilationUnit> parsedCUs = new HashMap<>();
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(),                       // resolves JDK types
                new JavaParserTypeSolver(sourceRoot.toFile())     // resolves project source
        );
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver);
        JavaParser parser = new JavaParser(config);

        // Walk directory for .java files
        Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(javaFile -> {
                    try {
                        ParseResult<CompilationUnit> result = parser.parse(javaFile);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            parsedCUs.put(javaFile.toAbsolutePath().toString(), result.getResult().get());
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to parse " + javaFile + ": " + e.getMessage());
                    }
                });

        return parsedCUs;
    }

    /**
     * Build a reverse call graph (calleeSignature -> set of callerSignatures)
     * for all methods in packagePrefix (e.g., "com.hack.parser.test").
     */
    public static Map<String, Set<String>> buildReverseCallGraph(
            Map<String, CompilationUnit> compilationUnits, String packagePrefix
    ) {
        Map<String, Set<String>> calleeToCallers = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            // 2a. Skip CUs not in desired package
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty() || !pkg.get().getNameAsString().startsWith(packagePrefix)) {
                continue; // skip unrelated packages :contentReference[oaicite:12]{index=12}
            }

            // 2b. Collect all top‐level & nested class/interface declarations
            List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class)
                    .stream()
                    .filter(coid -> {
                        Optional<String> fq = coid.getFullyQualifiedName();
                        return fq.isPresent() && fq.get().startsWith(packagePrefix);
                    })
                    .collect(Collectors.toList());

            for (ClassOrInterfaceDeclaration coid : classes) {
                // 2c. For each method, build reverse edges for every MethodCallExpr inside it
                for (MethodDeclaration method : coid.getMethods()) {
                    //String callerFQN = method.resolve().getQualifiedSignature();

                    ResolvedMethodDeclaration resolved = method.resolve();

                    String className = resolved.getPackageName() + "." + resolved.getClassName();
                    String methodName = resolved.getName();

                    String callerFQN = className + "." + methodName + "(" +
                            IntStream.range(0, resolved.getNumberOfParams())
                                    .mapToObj(i -> resolved.getParam(i).describeType())
                                    .collect(Collectors.joining(", ")) + ")";


                    // Visit every method call expression in this method body
                    method.findAll(MethodCallExpr.class).forEach(callExpr -> {
                        try {
                            ResolvedMethodDeclaration resolvedCallee = callExpr.resolve();
                            String calleeFQN = resolvedCallee.getQualifiedSignature();
                            // Register caller -> callee in reverse map: callee->caller
                            calleeToCallers
                                    .computeIfAbsent(calleeFQN, k -> new HashSet<>())
                                    .add(callerFQN);
                        } catch (UnsolvedSymbolException use) {
                            // skip calls that cannot be resolved :contentReference[oaicite:13]{index=13}
                        } catch (Exception ex) {
                            // skip any other resolution failures
                        }
                    });
                }
            }
        }

        return calleeToCallers;
    }


    // … (parseAllJavaFiles and buildReverseCallGraph as above) …

    /**
     * 3. Recursively collect and print all ordered caller chains from 'currentSig' up to methods
     * with no further callers. For each signature in a completed chain, print its full method source.
     *
     * @param currentSig       the current callee signature (starts as the targetSignature)
     * @param reverseCallGraph mapping each calleeSignature to its direct callers
     * @param pathSoFar        a Deque representing the chain from target → ... → currentSig
     * @param visited          signatures already in pathSoFar, to avoid cycles
     * @param signatureToNode  a map from methodSignature → MethodDeclaration (parsed AST node)
     * @return
     */
    public static String collectCallerPaths(
            String currentSig,
            Map<String, Set<String>> reverseCallGraph,
            Deque<String> pathSoFar,
            Set<String> visited,
            Map<String, MethodDeclaration> signatureToNode
    ) {

        // Look up direct callers of currentSig
        Set<String> directCallers = reverseCallGraph.getOrDefault(currentSig, Collections.emptySet());

        // Base case: if no callers, we have a complete path
        if (directCallers.isEmpty()) {
            StringBuilder sb = new StringBuilder();

            // Print the entire chain, with full source for each method in order
            //System.out.println("=== Complete Caller Chain ===");
            sb.append("=== Complete Caller Chain ===\n");
            for (String sig : pathSoFar) {
                MethodDeclaration node = signatureToNode.get(sig);
                if (node != null) {
                    // Print signature on one line, then full method (including comments) in a block
                    sb.append("\n--- ").append(sig).append(" ---\n").append(node.toString()).append("\n");
                    //System.out.println("\n--- " + sig + " ---");
                    //System.out.println(node.toString()); // includes comments and JavaDocs :contentReference[oaicite:5]{index=5}
                } else {
                    sb.append("\n--- ").append(sig).append(" (node not found in AST) ---\n");
                    //System.out.println("\n--- " + sig + " (node not found in AST) ---");
                }
            }
            sb.append("=============================\n");
            //System.out.println("=============================\n");
            return sb.toString();
        }

        // Otherwise, for each direct caller, recurse further
        StringBuilder callerPaths = new StringBuilder();
        for (String callerSig : directCallers) {
            if (visited.contains(callerSig)) {
                continue; // avoid infinite loops caused by recursion :contentReference[oaicite:6]{index=6}
            }
            // Mark as visited, push to path
            visited.add(callerSig);
            pathSoFar.addLast(callerSig);

            // Recurse on the callerSig
            callerPaths.append("\n")
                    .append(collectCallerPaths(callerSig, reverseCallGraph, pathSoFar, visited, signatureToNode))
                    .append("\n");

            // Backtrack
            pathSoFar.removeLast();
            visited.remove(callerSig);
        }
        return callerPaths.toString();
    }

    /**
     * 4. Main: (Call directly for testing) parse, build reverse graph, set up for DFS, print ordered caller chains + full method sources.
     */

//    public static void main(String[] args) throws IOException {
//        // You can also accept args[0] as the target signature, but we’ll hardcode for demonstration:
//        String targetSignature = "com.hack.parser.test.Helper.helperMethod()";
//        String packagePrefix = "com.hack.parser.test";
//        Path sourceRoot = Paths.get("src/main/java");
//
//        calculatePrintCallerPaths(sourceRoot, packagePrefix, targetSignature);
//    }

    public static void calculatePrintCallerPaths(Path sourceRoot, String packagePrefix, String targetSignature) throws IOException {
        // 4.1 Parse all .java files under src/main/java
        Map<String, CompilationUnit> allCUs = parseAllJavaFiles(sourceRoot);
        // 4.2 Build reverse call graph for everything in com.hack.parser.test
        Map<String, Set<String>> reverseGraph = buildReverseCallGraph(allCUs, packagePrefix);
        // 4.3 Build a signature→MethodDeclaration map for quick lookup
        Map<String, MethodDeclaration> signatureToNode = new HashMap<>();
        allCUs.values().forEach(cu -> {
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                try {
                    ResolvedMethodDeclaration rmd = method.resolve();
                    // e.g. com.hack.parser.test.TestSample.methodC(java.lang.String)
                    String sig = rmd.getQualifiedSignature();
                    signatureToNode.put(sig, method);
                } catch (Exception ignore) {
                    // skip unresolved methods
                }
            });
        });

        // 4.4 Prepare the DFS: pathSoFar (start with only the target), and visited set
        Deque<String> pathSoFar = new ArrayDeque<>();
        pathSoFar.addLast(targetSignature);

        Set<String> visited = new HashSet<>();
        visited.add(targetSignature);

        // 4.5 Recursively collect and print each ordered caller chain
        System.out.println("Finding all ordered caller chains for: " + targetSignature + "\n");
        String callerPaths = collectCallerPaths(targetSignature, reverseGraph, pathSoFar, visited, signatureToNode);
        System.out.println("callerPaths:" + callerPaths);
    }
}