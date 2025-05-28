package com.hack.parser.solver.enhanced;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Enhanced method caller finder that outputs results in JSON tree format with method bodies and comments
 */

@Slf4j
public class MethodCallFinder {

    private final JavaParser parser;
    private final String packagePrefix;
    private final Path sourceRoot;
    private Map<String, String> methodToFilePath = new HashMap<>();
    private Map<String, Integer> methodToLineNumber = new HashMap<>();
    private Map<String, MethodDeclaration> methodSignatureToDeclaration = new HashMap<>();
    private Map<String, String> filePathToContent = new HashMap<>();

    public MethodCallFinder(Path sourceRoot, String packagePrefix) {
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
                log.info("No method found at line " + lineNumber + " in class " + fullyQualifiedClassName);
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

            // Collect all unique methods from the tree
            Set<String> allMethodSignatures = new HashSet<>();
            collectMethodSignatures(rootNode, allMethodSignatures);

            // Extract method details for each unique method
            List<MethodDetails> methodDetails = extractMethodDetails(allMethodSignatures);

            // Convert to JSON and output
            outputJsonTree(rootNode, methodDetails);

        } catch (Exception e) {
            log.info("Error analyzing caller chains: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void collectMethodSignatures(CallTreeNode node, Set<String> signatures) {
        if (node.method != null && !node.method.equals("ROOT")) {
            signatures.add(node.method);
        }

        for (CallTreeNode child : node.children) {
            collectMethodSignatures(child, signatures);
        }
    }

    private List<MethodDetails> extractMethodDetails(Set<String> methodSignatures) {
        List<MethodDetails> methodDetails = new ArrayList<>();

        for (String signature : methodSignatures) {
            MethodDeclaration methodDecl = methodSignatureToDeclaration.get(signature);
            if (methodDecl != null) {
                MethodDetails details = new MethodDetails();
                details.name = extractMethodName(signature);
                details.signature = signature;
                details.body = extractMethodBody(methodDecl);
                details.comments = extractMethodComments(methodDecl);
                methodDetails.add(details);
            } else {
                // Create basic details for unresolved methods
                MethodDetails details = new MethodDetails();
                details.name = extractMethodName(signature);
                details.signature = signature;
                details.body = "// Method body not available (external or unresolved)";
                details.comments = "";
                methodDetails.add(details);
            }
        }

        return methodDetails;
    }

    private String extractMethodName(String signature) {
        // Extract just the method name from the full signature
        // Example: "com.example.Class.methodName(java.lang.String, int)" -> "methodName"
        try {
            // Find the opening parenthesis to locate where parameters start
            int parenIndex = signature.indexOf('(');
            if (parenIndex > 0) {
                // Get everything before the parenthesis
                String beforeParen = signature.substring(0, parenIndex);
                // Find the last dot before the parenthesis (separates class from method name)
                int lastDotIndex = beforeParen.lastIndexOf('.');
                if (lastDotIndex >= 0) {
                    return beforeParen.substring(lastDotIndex + 1);
                } else {
                    return beforeParen; // No dots found, return as-is
                }
            }
            // No parenthesis found, try to extract from end
            int lastDotIndex = signature.lastIndexOf('.');
            if (lastDotIndex >= 0) {
                return signature.substring(lastDotIndex + 1);
            }
            return signature; // Fallback to full signature
        } catch (Exception e) {
            return signature; // Fallback to full signature
        }
    }

    private String extractMethodBody(MethodDeclaration method) {
        try {
            if (method.getBody().isPresent()) {
                // Try to get original formatted body from source file
                String formattedBody = getOriginalFormattedBody(method);
                if (formattedBody != null) {
                    return formattedBody;
                }

                // Fallback to JavaParser's toString() but with better formatting
                return method.getBody().get().toString();
            } else {
                return "// Abstract method or interface method - no body";
            }
        } catch (Exception e) {
            return "// Error extracting method body: " + e.getMessage();
        }
    }

    private String getOriginalFormattedBody(MethodDeclaration method) {
        try {
            // Find the file path for this method
            String signature = getMethodSignature(method);
            String filePath = methodToFilePath.get(signature);

            if (filePath == null) {
                return null;
            }

            // Get the file content
            String fileContent = filePathToContent.get(filePath);
            if (fileContent == null) {
                return null;
            }

            // Get the method body range
            if (method.getBody().isPresent() && method.getBody().get().getRange().isPresent()) {
                var bodyRange = method.getBody().get().getRange().get();

                // Split file content into lines
                String[] lines = fileContent.split("\n");

                // Extract lines from start to end of method body (1-based to 0-based conversion)
                int startLine = bodyRange.begin.line - 1;
                int endLine = bodyRange.end.line - 1;

                if (startLine >= 0 && endLine < lines.length && startLine <= endLine) {
                    StringBuilder formattedBody = new StringBuilder();

                    for (int i = startLine; i <= endLine; i++) {
                        if (i > startLine) {
                            formattedBody.append("\n");
                        }
                        formattedBody.append(lines[i]);
                    }

                    return formattedBody.toString();
                }
            }

            return null;
        } catch (Exception e) {
            return null; // Fall back to default toString() method
        }
    }

    private String extractMethodComments(MethodDeclaration method) {
        StringBuilder comments = new StringBuilder();

        try {
            // Get Javadoc comment
            if (method.getJavadocComment().isPresent()) {
                JavadocComment javadoc = method.getJavadocComment().get();
                comments.append("/**\n").append(javadoc.getContent()).append("\n*/\n");
            }

            // Get regular comment associated with the method
            if (method.getComment().isPresent()) {
                Comment comment = method.getComment().get();
                comments.append(comment.toString()).append("\n");
            }

            // Get orphan comments that might be associated with this method
            if (method.getParentNode().isPresent()) {
                List<Comment> orphanComments = method.getParentNode().get().getOrphanComments();
                for (Comment orphanComment : orphanComments) {
                    // Check if orphan comment is near this method (rough heuristic)
                    if (orphanComment.getRange().isPresent() && method.getRange().isPresent()) {
                        int commentLine = orphanComment.getRange().get().begin.line;
                        int methodLine = method.getRange().get().begin.line;
                        // Include orphan comments that are within 3 lines before the method
                        if (commentLine >= methodLine - 3 && commentLine < methodLine) {
                            comments.append(orphanComment.toString()).append("\n");
                        }
                    }
                }
            }

        } catch (Exception e) {
            comments.append("// Error extracting comments: ").append(e.getMessage());
        }

        return comments.toString().trim();
    }

    private void buildMethodMetadata(Map<String, CompilationUnit> compilationUnits) {
        for (Map.Entry<String, CompilationUnit> entry : compilationUnits.entrySet()) {
            String filePath = entry.getKey();
            CompilationUnit cu = entry.getValue();

            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                try {
                    String signature = getMethodSignature(method);
                    methodToFilePath.put(signature, filePath);
                    methodSignatureToDeclaration.put(signature, method);
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
                        // Read file content for formatting preservation
                        String fileContent = new String(Files.readAllBytes(javaFile), StandardCharsets.UTF_8);
                        filePathToContent.put(javaFile.toString(), fileContent);

                        ParseResult<CompilationUnit> result = parser.parse(javaFile);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            compilationUnits.put(javaFile.toString(), result.getResult().get());
                        }
                    } catch (IOException e) {
                        log.info("Failed to parse " + javaFile + ": " + e.getMessage());
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
        log.info("=== CALL GRAPH DEBUG ===");
        for (Map.Entry<String, Set<String>> entry : callGraph.entrySet()) {
            log.info(entry.getKey() + " -> " + entry.getValue());
        }
        log.info("========================");

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

    private void outputJsonTree(CallTreeNode rootNode, List<MethodDetails> methodDetails) {
        // Create the final JSON structure
        JsonOutput output = new JsonOutput();

        if (!rootNode.children.isEmpty()) {
            output.dag_tree = rootNode.children.get(0); // Take the first (and likely only) entry point
        } else {
            output.dag_tree = rootNode;
        }

        output.methods = methodDetails;

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println("TREE_DAG_JSON:");
        System.out.println(gson.toJson(output));
    }

    // JSON structure classes
    static class JsonOutput {
        CallTreeNode dag_tree;
        List<MethodDetails> methods;
    }

    static class CallTreeNode {
        String method;
        String file;
        int line;
        List<CallTreeNode> children = new ArrayList<>();
    }

    static class MethodDetails {
        String name;
        String signature;
        String body;
        String comments;
    }

    // Main method for testing
    public static void main(String[] args) {
        String fullyQualifiedClassName = "com.hack.parser.test.InnerHelperImpl2";
        int lineNumber = 32;
        Path sourceRoot = Paths.get("src/main/java");
        String packagePrefix = "com.hack.parser.test";

        if (args.length >= 4) {
            fullyQualifiedClassName = args[0];
            try {
                lineNumber = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                log.info("Invalid line number: " + args[1]);
                System.exit(1);
            }
            sourceRoot = Paths.get(args[2]);
            packagePrefix = args[3];
        }

        MethodCallFinder finder = new MethodCallFinder(sourceRoot, packagePrefix);
        finder.findCallerChains(sourceRoot, fullyQualifiedClassName, lineNumber);
    }
}