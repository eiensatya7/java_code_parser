package com.hack.parser.solver.enhanced;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hack.parser.solver.deprecated.EnhancedMethodCallerFinderWithJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Enhanced method caller finder that outputs results in JSON tree format
 */
public class MethodCallFinderOlder {

    private final JavaParser parser;
    private final String packagePrefix;
    private final Path sourceRoot;
    private Map<String, String> methodToFilePath = new HashMap<>();
    private Map<String, Integer> methodToLineNumber = new HashMap<>();

    public MethodCallFinderOlder(Path sourceRoot, String packagePrefix) {
        this.packagePrefix = packagePrefix;
        this.sourceRoot = sourceRoot;
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
     * Main entry point to find and output caller chains as JSON
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

            // Build method metadata maps
            buildMethodMetadata(compilationUnits);

            // Build interface implementation mapping
            Map<String, Set<String>> interfaceToImplementations = buildInterfaceImplementationMap(compilationUnits);

            // Build call graph (forward direction)
            Map<String, Set<String>> callGraph = buildEnhancedCallGraph(compilationUnits, interfaceToImplementations);

            // Get target method signature
            String targetSignature = getMethodSignature(targetMethod);

            // Build tree starting from all entry points
            CallTreeNode rootNode = buildCallTree(targetSignature, callGraph);

            // Convert to JSON and output
            outputJsonTree(rootNode);

        } catch (Exception e) {
            System.err.println("Error analyzing caller chains: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void buildMethodMetadata(Map<String, CompilationUnit> compilationUnits) {
        for (Map.Entry<String, CompilationUnit> entry : compilationUnits.entrySet()) {
            String filePath = entry.getKey();
            CompilationUnit cu = entry.getValue();

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                try {
                    String signature = getMethodSignature(method);
                    methodToFilePath.put(signature, filePath);
                    if (method.getRange().isPresent()) {
                        methodToLineNumber.put(signature, method.getRange().get().begin.line);
                    }
                } catch (Exception e) {
                    // Skip unresolvable methods
                }
            }
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

    private Map<String, Set<String>> buildInterfaceImplementationMap(Map<String, CompilationUnit> compilationUnits) {
        Map<String, Set<String>> interfaceToImpls = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty() || !pkg.get().getNameAsString().startsWith(packagePrefix)) continue;

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!clazz.isInterface() && !clazz.getImplementedTypes().isEmpty()) {
                    for (MethodDeclaration method : clazz.getMethods()) {
                        try {
                            String implSignature = getMethodSignature(method);

                            for (var implementedType : clazz.getImplementedTypes()) {
                                try {
                                    ResolvedReferenceTypeDeclaration resolvedInterface =
                                            implementedType.resolve().asReferenceType().getTypeDeclaration().get();

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
     * Build enhanced call graph (caller -> callees) with better debugging
     */
    private Map<String, Set<String>> buildEnhancedCallGraph(
            Map<String, CompilationUnit> compilationUnits,
            Map<String, Set<String>> interfaceToImplementations) {

        Map<String, Set<String>> callGraph = new HashMap<>();

        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty() || !pkg.get().getNameAsString().startsWith(packagePrefix)) continue;

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String className = pkg.get().getNameAsString() + "." + clazz.getNameAsString();
                if (!className.startsWith(packagePrefix)) continue;

                for (MethodDeclaration method : clazz.getMethods()) {
                    String callerSignature = getMethodSignature(method);

                    for (MethodCallExpr callExpr : method.findAll(MethodCallExpr.class)) {
                        try {
                            ResolvedMethodDeclaration resolvedCallee = callExpr.resolve();
                            String calleeSignature = resolvedCallee.getQualifiedSignature();

                            // Add direct call relationship
                            callGraph.computeIfAbsent(callerSignature, k -> new HashSet<>())
                                    .add(calleeSignature);

                            // If this is an interface call, also add relationships to implementations
                            Set<String> implementations = interfaceToImplementations.get(calleeSignature);
                            if (implementations != null) {
                                for (String implSignature : implementations) {
                                    callGraph.computeIfAbsent(callerSignature, k -> new HashSet<>())
                                            .add(implSignature);
                                }
                            }

                        } catch (Exception e) {
                            // Skip unresolvable calls
                        }
                    }
                }
            }
        }

        // Debug: Print call graph
        System.err.println("=== CALL GRAPH DEBUG ===");
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            System.err.println(entry.getKey() + " -> " + entry.getValue());
        }
        System.err.println("========================");

        return callGraph;
    }

    private CallTreeNode buildCallTree(String targetSignature, Map<String, Set<String>> callGraph) {
        // Find entry points (methods with no callers or main methods)
        Set<String> allMethods = new HashSet<>(callGraph.keySet());
        Set<String> calledMethods = new HashSet<>();

        // Collect all called methods
        for (Set<String> callees : callGraph.values()) {
            calledMethods.addAll(callees);
        }

        // Entry points are methods that are never called by others, or are main methods
        Set<String> entryPoints = new HashSet<>();
        for (String method : allMethods) {
            if (!calledMethods.contains(method) || method.contains("main(")) {
                entryPoints.add(method);
            }
        }

        // Build tree from entry points that can reach the target
        CallTreeNode root = new CallTreeNode();
        root.method = "ROOT";
        root.children = new ArrayList<>();

        for (String entryPoint : entryPoints) {
            CallTreeNode entryNode = buildTreeFromEntryPoint(entryPoint, targetSignature, callGraph, new HashSet<>());
            if (entryNode != null) {
                root.children.add(entryNode);
            }
        }

        // If no entry points found, try to find any path to target
        if (root.children.isEmpty()) {
            // Look for any method that can reach the target
            for (String method : allMethods) {
                CallTreeNode node = buildTreeFromEntryPoint(method, targetSignature, callGraph, new HashSet<>());
                if (node != null) {
                    root.children.add(node);
                    break; // Just take the first valid path
                }
            }
        }

        return root;
    }

    private CallTreeNode buildTreeFromEntryPoint(String currentSignature, String targetSignature,
                                                 Map<String, Set<String>> callGraph, Set<String> visited) {
        if (visited.contains(currentSignature)) {
            return null; // Avoid cycles
        }

        visited.add(currentSignature);

        // Create node for current method
        CallTreeNode node = createNodeFromSignature(currentSignature);

        // If this is the target method, we found our destination
        if (currentSignature.equals(targetSignature)) {
            visited.remove(currentSignature);
            return node;
        }

        // Recursively build children for all callees that are in our package
        Set<String> callees = callGraph.getOrDefault(currentSignature, new HashSet<>());
        boolean hasValidChildren = false;

        for (String callee : callees) {
            // Only include callees that are part of our package prefix
            if (callee.startsWith(packagePrefix)) {
                CallTreeNode childNode = buildTreeFromEntryPoint(callee, targetSignature, callGraph, new HashSet<>(visited));
                if (childNode != null) {
                    node.children.add(childNode);
                    hasValidChildren = true;
                }
            }
        }

        visited.remove(currentSignature);

        // Return node only if it's the target or has children that lead to target
        return (hasValidChildren || currentSignature.equals(targetSignature)) ? node : null;
    }

    private boolean canReachTarget(CallTreeNode node, String targetSignature) {
        if (node.method.equals(targetSignature)) {
            return true;
        }

        for (CallTreeNode child : node.children) {
            if (canReachTarget(child, targetSignature)) {
                return true;
            }
        }

        return false;
    }

    private CallTreeNode createNodeFromSignature(String signature) {
        CallTreeNode node = new CallTreeNode();
        node.method = signature;
        node.file = methodToFilePath.getOrDefault(signature, "unknown");
        node.line = methodToLineNumber.getOrDefault(signature, 0);
        node.children = new ArrayList<>();
        return node;
    }

    private String getMethodSignature(MethodDeclaration method) {
        try {
            return method.resolve().getQualifiedSignature();
        } catch (Exception e) {
            // Fallback to simple signature
            return method.getDeclarationAsString(false, false, false);
        }
    }

    private void outputJsonTree(CallTreeNode rootNode) {
        // Create the final JSON structure
        JsonOutput output = new JsonOutput();

        if (!rootNode.children.isEmpty()) {
            output.root = rootNode.children.get(0); // Take the first (and likely only) entry point
        } else {
            output.root = rootNode;
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println("TREE_DAG_JSON:");
        System.out.println(gson.toJson(output));
    }

    // JSON structure classes
    static class JsonOutput {
        CallTreeNode root;
    }

    static class CallTreeNode {
        String method;
        String file;
        int line;
        List<CallTreeNode> children = new ArrayList<>();
    }

    // Main method for testing
    public static void main(String[] args) {
        String fullyQualifiedClassName = "com.hack.parser.test.InnerHelperImpl2";
        int lineNumber = 28;
        Path sourceRoot = Paths.get("src/main/java");
        String packagePrefix = "com.hack.parser.test";

        if (args.length >= 4) {
            fullyQualifiedClassName = args[0];
            try {
                lineNumber = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid line number: " + args[1]);
                System.exit(1);
            }
            sourceRoot = Paths.get(args[2]);
            packagePrefix = args[3];
        }

        EnhancedMethodCallerFinderWithJson finder = new EnhancedMethodCallerFinderWithJson(sourceRoot, packagePrefix);
        finder.findCallerChains(sourceRoot, fullyQualifiedClassName, lineNumber);
    }
}