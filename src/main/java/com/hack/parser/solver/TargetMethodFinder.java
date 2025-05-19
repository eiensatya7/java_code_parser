package com.hack.parser.solver;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.CompilationUnit;

import java.util.Map;
import java.util.Optional;

public class TargetMethodFinder {

    /**
     * Finds the MethodDeclaration within the specified class that contains the given line number.
     *
     * @param fullyQualifiedClassName e.g. "com.example.MyClass"
     * @param lineNumber              the line number to locate
     * @param compilationUnits        map from file path to CompilationUnit
     * @return Optional containing the MethodDeclaration if found; empty otherwise
     */
    public static Optional<MethodDeclaration> findMethodByLine(
            String fullyQualifiedClassName,
            int lineNumber,
            Map<String, CompilationUnit> compilationUnits
    ) {
        // Extract simple class name (after last dot) and expected package name
        String simpleClassName;
        String expectedPackage = "";
        if (fullyQualifiedClassName.contains(".")) {
            int lastDot = fullyQualifiedClassName.lastIndexOf('.');
            expectedPackage = fullyQualifiedClassName.substring(0, lastDot);
            simpleClassName = fullyQualifiedClassName.substring(lastDot + 1);
        } else {
            simpleClassName = fullyQualifiedClassName;
        }

        // Iterate over all parsed CUs to find the one matching the class
        for (Map.Entry<String, CompilationUnit> entry : compilationUnits.entrySet()) {
            CompilationUnit cu = entry.getValue();
            // Check package declaration matches (if any)
            Optional<String> pkgName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString());
            if (!expectedPackage.isEmpty()) {
                if (pkgName.isEmpty() || !pkgName.get().equals(expectedPackage)) {
                    continue;
                }
            }

            // Within that CU, look for a top-level type with the matching simple name
            NodeList<TypeDeclaration<?>> types = cu.getTypes();
            for (TypeDeclaration<?> typeDec : types) {
                if (typeDec instanceof ClassOrInterfaceDeclaration) {
                    ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) typeDec;
                    if (cid.getNameAsString().equals(simpleClassName)) {
                        // Found the target class––search its methods
                        for (MethodDeclaration method : cid.getMethods()) {
                            if (method.getRange().isPresent()) {
                                int beginLine = method.getRange().get().begin.line;
                                int endLine = method.getRange().get().end.line;
                                // Check if the given lineNumber falls within [beginLine, endLine]
                                if (lineNumber >= beginLine && lineNumber <= endLine) {
                                    return Optional.of(method);
                                }
                            }
                        }
                        // If we didn’t find it among direct methods, maybe nested/inner classes?
                        // For simplicity, we only check top-level class methods here.
                    }
                }
            }
        }

        return Optional.empty();
    }
}
