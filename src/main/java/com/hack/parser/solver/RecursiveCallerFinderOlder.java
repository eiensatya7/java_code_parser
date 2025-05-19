package com.hack.parser.solver;

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

/**
 * A utility that builds a reverse call graph for all methods in a given package
 * and then recursively finds transitive callers of a target method.
 */
public class RecursiveCallerFinderOlder {

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


    /**
     * Recursively collects or prints all ordered caller paths from 'currentSig' up to roots.
     *
     * @param currentSig      the current callee signature (starting with targetSignature)
     * @param reverseCallGraph mapping each callee to its direct callers
     * @param pathSoFar        a Deque representing the path from initial target → ... → currentSig
     * @param visited          a Set of signatures already on pathSoFar to avoid cycles
     */
    public static void collectCallerPaths(
            String currentSig,
            Map<String, Set<String>> reverseCallGraph,
            Deque<String> pathSoFar,
            Set<String> visited
    ) {
        // Look up direct callers of currentSig
        Set<String> directCallers = reverseCallGraph.getOrDefault(currentSig, Collections.emptySet());

        // If no callers, we’ve reached the top; print the entire pathSoFar (which includes currentSig)
        if (directCallers.isEmpty()) {
            // Print in order: target → ... → currentSig
            System.out.println(String.join(" → ", pathSoFar)); // :contentReference[oaicite:12]{index=12}
            return;
        }

        // Otherwise, for each direct caller, recurse further
        for (String callerSig : directCallers) {
            // If we already visited this callerSig, skip to avoid infinite recursion
            if (visited.contains(callerSig)) {
                continue; // avoid cycles :contentReference[oaicite:12]{index=12}
            }

            // Mark as visited and add to path
            visited.add(callerSig);
            pathSoFar.addLast(callerSig);

            // Recurse to explore further callers up the chain
            collectCallerPaths(callerSig, reverseCallGraph, pathSoFar, visited);

            // Backtrack: remove from path and visited
            pathSoFar.removeLast();
            visited.remove(callerSig);
        }
    }

    /**
     * Given a reverse call graph (callee -> set of direct callers), perform a DFS
     * to collect all transitive callers of the target methodSignature.
     */
    public static Set<String> findAllTransitiveCallers(
            String targetMethodSignature, Map<String, Set<String>> reverseCallGraph
    ) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(targetMethodSignature);

        while (!stack.isEmpty()) {
            String callee = stack.pop();
            // For each direct caller of this callee
            Set<String> directCallers = reverseCallGraph.getOrDefault(callee, Collections.emptySet());
            for (String caller : directCallers) {
                if (!visited.contains(caller)) {
                    visited.add(caller);
                    stack.push(caller);
                }
            }
        }
        // 'visited' now contains all callers up the chain (excluding the target itself)
        return visited;
    }

    /**
     * Print the source code of each method in methodSignatures, looking up their
     * MethodDeclaration in the parsed CompilationUnits.
     */
    public static void printCallerMethods(
            Set<String> methodSignatures, Map<String, CompilationUnit> compilationUnits
    ) {
        Map<String, MethodDeclaration> signatureToNode = new HashMap<>();

        // Build a map from signature -> MethodDeclaration for quick lookup
        compilationUnits.values().forEach(cu -> {
            cu.findAll(MethodDeclaration.class).forEach(method -> {
                try {
                    ResolvedMethodDeclaration resolved = method.resolve();
                    signatureToNode.put(resolved.getQualifiedSignature(), method);
                } catch (Exception ignore) {
                    // unresolved method; skip
                }
            });
        });

        // Print each caller’s method code
        for (String sig : methodSignatures) {
            MethodDeclaration callerNode = signatureToNode.get(sig);
            if (callerNode != null) {
                System.out.println("=== Caller: " + sig + " ===");
                System.out.println(callerNode.toString());
                System.out.println("================================\n");
            } else {
                System.out.println("*** Signature not found in AST: " + sig + " ***\n");
            }
        }
    }

    /**
     * Example main that ties it all together:
     * 1. Parse all .java files under src/main/java
     * 2. Build reverse call graph for "com.hack.parser.test"
     * 3. Find all transitive callers of target method signature
     * 4. Print source code of each discovered caller
     */
    public static void main2(String[] args) throws IOException {
//        if (args.length < 1) {
//            System.err.println("Usage: java RecursiveCallerFinder <targetMethodQualifiedSignature>");
//            System.err.println("Example signature: com.hack.parser.test.Helper.helperMethod()");
//            System.exit(1);
//        }
        String targetSignature = "com.hack.parser.test.Helper.helperMethod()";

        // 1. Parse project
        Path sourceRoot = Paths.get("src/main/java");
        Map<String, CompilationUnit> allCUs = parseAllJavaFiles(sourceRoot);

        // 2. Build reverse call graph for methods in com.hack.parser.test
        String packagePrefix = "com.hack.parser.test";
        Map<String, Set<String>> reverseGraph = buildReverseCallGraph(allCUs, packagePrefix);

        // 3. Find all transitive callers of the target method
        Set<String> callers = findAllTransitiveCallers(targetSignature, reverseGraph);
        if (callers.isEmpty()) {
            System.out.println("No callers found for " + targetSignature);
        } else {
            System.out.println("Found " + callers.size() + " transitive caller(s) of " + targetSignature + ":");
            // 4. Print caller methods’ code
            printCallerMethods(callers, allCUs);
        }
    }


public static void main(String[] args) throws IOException {
    // For simplicity, we hardcode targetSignature here:
    String targetSignature = "com.hack.parser.test.Helper.helperMethod()"; //:contentReference[oaicite:18]{index=18}

    // 1. Parse all .java files under src/main/java
    Path sourceRoot = Paths.get("src/main/java");
    Map<String, CompilationUnit> allCUs = parseAllJavaFiles(sourceRoot);

    // 2. Build the reverse call graph for "com.hack.parser.test"
    String packagePrefix = "com.hack.parser.test";
    Map<String, Set<String>> reverseGraph = buildReverseCallGraph(allCUs, packagePrefix);

    // 3. Prepare for DFS: initial path contains only the target signature
    Deque<String> pathSoFar = new ArrayDeque<>();
    pathSoFar.addLast(targetSignature);

    // 4. Prepare visited set (to avoid cycles); start with targetSignature
    Set<String> visited = new HashSet<>();
    visited.add(targetSignature);

    // 5. Recursively collect and print all ordered caller chains
    System.out.println("All caller chains for: " + targetSignature);
    collectCallerPaths(targetSignature, reverseGraph, pathSoFar, visited); //:contentReference[oaicite:19]{index=19}
}
}