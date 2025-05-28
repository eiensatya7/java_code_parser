package com.hack.parser.solver.deprecated;

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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Enhanced method caller finder that outputs results in JSON tree format
 */
public class EnhancedMethodCallerFinderWithJson {

    private final JavaParser parser;
    private final String packagePrefix;
    private final Path sourceRoot;
    private Map<String, String> methodToFilePath = new HashMap<>();
    private Map<String, Integer> methodToLineNumber = new HashMap<>();

    public EnhancedMethodCallerFinderWithJson(Path sourceRoot, String packagePrefix) {
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
     * Build forward call graph (caller -> callees)
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

        return callGraph;
    }

    private CallTreeNode buildCallTree(String targetSignature, Map<String, Set<String>> callGraph) {
        // Find all paths that lead to the target method
        Map<String, Set<String>> reverseCallGraph = new HashMap<>();

        // Build reverse call graph
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            String caller = entry.getKey();
            for (String callee : entry.getValue()) {
                reverseCallGraph.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
            }
        }

        // Find entry points (methods with no callers)
        Set<String> entryPoints = new HashSet<>();
        for (String method : callGraph.keySet()) {
            if (!reverseCallGraph.containsKey(method) || reverseCallGraph.get(method).isEmpty()) {
                entryPoints.add(method);
            }
        }

        // Build tree from entry points that can reach the target
        CallTreeNode root = new CallTreeNode();
        root.method = "ROOT";
        root.children = new ArrayList<>();

        for (String entryPoint : entryPoints) {
            CallTreeNode entryNode = buildTreeNode(entryPoint, targetSignature, callGraph, new HashSet<>());
            if (entryNode != null && canReachTarget(entryNode, targetSignature)) {
                root.children.add(entryNode);
            }
        }

        // If no entry points found, create a simplified tree
        if (root.children.isEmpty()) {
            CallTreeNode targetNode = createNodeFromSignature(targetSignature);
            root.children.add(targetNode);
        }

        return root;
    }

    private CallTreeNode buildTreeNode(String currentSignature, String targetSignature,
                                       Map<String, Set<String>> callGraph, Set<String> visited) {
        if (visited.contains(currentSignature)) {
            return null; // Avoid cycles
        }

        visited.add(currentSignature);
        CallTreeNode node = createNodeFromSignature(currentSignature);

        if (currentSignature.equals(targetSignature)) {
            // Target reached - no children needed
            visited.remove(currentSignature);
            return node;
        }

        Set<String> callees = callGraph.getOrDefault(currentSignature, new HashSet<>());
        for (String callee : callees) {
            // Only include callees that are part of our package prefix (exclude system calls like println)
            if (callee.startsWith(packagePrefix)) {
                CallTreeNode childNode = buildTreeNode(callee, targetSignature, callGraph, visited);
                if (childNode != null) {
                    node.children.add(childNode);
                }
            }
        }

        visited.remove(currentSignature);

        // Only return this node if it can reach the target (has children or is the target)
        return (node.children.isEmpty() && !currentSignature.equals(targetSignature)) ? null : node;
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