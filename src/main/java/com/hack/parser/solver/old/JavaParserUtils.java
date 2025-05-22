package com.hack.parser.solver.old;

import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class JavaParserUtils {

    /**
     * Walks the provided sourceFolderPath and parses every .java file into a CompilationUnit.
     *
     * @param sourceFolderPath the root directory containing Java source files
     * @return a Map from the absolute file path string to its parsed CompilationUnit
     * @throws IOException if an IO error occurs while walking the file tree
     */
    public static Map<String, CompilationUnit> parseAllJavaFiles(Path sourceFolderPath) throws IOException {
        Map<String, CompilationUnit> compilationUnits = new HashMap<>();

        TypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(), // for resolving JDK classes, etc.
                new JavaParserTypeSolver(sourceFolderPath.toFile())
        );

        // 2. Attach symbol solver to the parser configuration
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        // Create a parser configuration that sets up the symbol resolver later
        ParserConfiguration configWithSolver = new ParserConfiguration().setSymbolResolver(symbolSolver);
        // We'll configure the symbol solver in the main flow, after parsing all CUs
        JavaParser javaParser = new JavaParser(configWithSolver);

        // Walk the directory tree looking for .java files
        Files.walk(sourceFolderPath)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(javaFilePath -> {
                    try {
                        // Parse the file into a CompilationUnit
                        ParseResult<CompilationUnit> result = javaParser.parse(javaFilePath);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            CompilationUnit cu = result.getResult().get();
                            compilationUnits.put(javaFilePath.toAbsolutePath().toString(), cu);
                        } else {
                            // Handle parse problems if needed
                            System.err.println("Failed to parse: " + javaFilePath);
                        }
                    } catch (IOException e) {
                        System.err.println("IOException parsing file " + javaFilePath + ": " + e.getMessage());
                    }
                });

        return compilationUnits;
    }
}