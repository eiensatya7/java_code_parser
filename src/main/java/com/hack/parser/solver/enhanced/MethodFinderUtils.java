package com.hack.parser.solver.enhanced;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.Map;
import java.util.Optional;

/**
 * Utility class for common method finding operations
 */
public class MethodFinderUtils {
    
    /**
     * Find a method declaration by class name and line number
     */
    public static MethodDeclaration findMethodByLine(String fullyQualifiedClassName, 
                                                   int lineNumber,
                                                   Map<String, CompilationUnit> compilationUnits) {
        for (CompilationUnit cu : compilationUnits.values()) {
            Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
            if (pkg.isEmpty()) continue;
            
            String packageName = pkg.get().getNameAsString();
            
            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String fullClassName = packageName + "." + clazz.getNameAsString();
                
                if (fullClassName.equals(fullyQualifiedClassName)) {
                    for (MethodDeclaration method : clazz.getMethods()) {
                        if (containsLine(method, lineNumber)) {
                            return method;
                        }
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Check if a method declaration contains the specified line number
     */
    public static boolean containsLine(MethodDeclaration method, int lineNumber) {
        return method.getRange().isPresent() &&
               method.getRange().get().begin.line <= lineNumber &&
               method.getRange().get().end.line >= lineNumber;
    }
    
    /**
     * Get the signature of a method declaration
     */
    public static String getMethodSignature(MethodDeclaration method) {
        try {
            ResolvedMethodDeclaration resolved = method.resolve();
            return resolved.getQualifiedSignature();
        } catch (Exception e) {
            // Fallback to declaration string if resolution fails
            return method.getDeclarationAsString(false, false, false);
        }
    }
    
    /**
     * Check if a compilation unit belongs to the specified package prefix
     */
    public static boolean belongsToPackage(CompilationUnit cu, String packagePrefix) {
        Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
        return pkg.isPresent() && pkg.get().getNameAsString().startsWith(packagePrefix);
    }
    
    /**
     * Get the fully qualified class name from a class declaration
     */
    public static String getFullyQualifiedClassName(ClassOrInterfaceDeclaration clazz, String packageName) {
        return packageName + "." + clazz.getNameAsString();
    }
    
    /**
     * Check if two method declarations have matching signatures (name and parameters)
     */
    public static boolean methodSignaturesMatch(MethodDeclaration method1, MethodDeclaration method2) {
        if (!method1.getNameAsString().equals(method2.getNameAsString())) {
            return false;
        }
        
        if (method1.getParameters().size() != method2.getParameters().size()) {
            return false;
        }
        
        try {
            ResolvedMethodDeclaration resolved1 = method1.resolve();
            ResolvedMethodDeclaration resolved2 = method2.resolve();
            
            for (int i = 0; i < resolved1.getNumberOfParams(); i++) {
                if (!resolved1.getParam(i).getType().equals(resolved2.getParam(i).getType())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // Fallback to basic comparison if resolution fails
            return method1.getSignature().equals(method2.getSignature());
        }
    }
}