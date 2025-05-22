package com.hack.parser.solver.fixed;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.hack.parser.solver.old.JavaParserUtils;
import com.hack.parser.solver.old.TargetMethodFinder;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FindMethodCallersApp {

    public static void main(String[] args) {
//        if (args.length < 3) {
//            System.err.println("Usage: java FindMethodCallersApp <FullyQualifiedClassName> <LineNumber> <SourceFolderPath>");
//            System.exit(1);
//        }

        String fullyQualifiedClassName = "com.hack.parser.test.HelperImpl"; // e.g. "com.example.MyClass"
        int lineNumber = 15; // e.g. 125
        Path sourceFolderPath = Paths.get("/Users/sai/Workspaces/hackathon_workspace/java_code_parser/src/main/java"); // e.g. "src/main/java"
        String packagePrefix = "com.hack.parser.test";
        Path sourceRoot = Paths.get("src/main/java");
        try {
            // 1. Parse all Java files into CompilationUnits
            Map<String, CompilationUnit> compilationUnits = JavaParserUtils.parseAllJavaFiles(sourceFolderPath);
            if (compilationUnits.isEmpty()) {
                System.err.println("No Java files found in " + sourceFolderPath);
                System.exit(2);
            }

            // 2. Locate the MethodDeclaration that contains the given line number in the specified class
            Optional<MethodDeclaration> targetOpt = TargetMethodFinder.findMethodByLine(
                    fullyQualifiedClassName, lineNumber, compilationUnits);

            if (!targetOpt.isPresent()) {
                System.err.println("No method found in class " + fullyQualifiedClassName +
                        " containing line " + lineNumber);
                System.exit(3);
            }

            MethodDeclaration targetMethod = targetOpt.get();
            System.out.println("Target method found: "
                    + targetMethod.getDeclarationAsString(false, false, false)
                    + " (lines " + targetMethod.getRange().get().begin.line
                    + "-" + targetMethod.getRange().get().end.line + ")\n");
            ResolvedMethodDeclaration resolved;
            try {
                resolved = targetMethod.resolve();

                String className = resolved.getPackageName() + "." + resolved.getClassName();
                String methodName = resolved.getName();

                String callerFQN = className + "." + methodName + "(" +
                        IntStream.range(0, resolved.getNumberOfParams())
                                .mapToObj(i -> resolved.getParam(i).describeType())
                                .collect(Collectors.joining(", ")) + ")";
                System.out.println("Caller FQN: " + callerFQN);
                System.out.println("Caller FQN2: " + resolved.getQualifiedSignature());

            } catch (Exception e) {
                throw new IllegalStateException("Unable to resolve target method: " + e.getMessage(), e);
            }


            RecursiveCallerFinder.calculatePrintCallerPaths(sourceFolderPath, packagePrefix, resolved.getQualifiedSignature());


        } catch (Exception e) {
            e.printStackTrace();
            System.exit(100);
        }
    }
}
