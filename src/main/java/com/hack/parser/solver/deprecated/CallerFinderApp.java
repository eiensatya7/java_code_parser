package com.hack.parser.solver.deprecated;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main application class for finding method callers
 * 
 * Usage: java CallerFinderApp [className] [lineNumber] [sourceRoot] [packagePrefix]
 */
public class CallerFinderApp {
    
    public static void main(String[] args) {
        // Default values for testing
        String fullyQualifiedClassName = "com.hack.parser.test.InnerHelperImpl2";
        int lineNumber = 16;
        Path sourceRoot = Paths.get("src/main/java");
        String packagePrefix = "com.hack.parser.test";
        
        // Parse command line arguments if provided
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
        } else if (args.length > 0) {
            System.err.println("Usage: java CallerFinderApp <FullyQualifiedClassName> <LineNumber> <SourceRoot> <PackagePrefix>");
            System.err.println("Example: java CallerFinderApp com.example.MyClass 25 src/main/java com.example");
            System.exit(1);
        }
        
        System.out.println("=== Method Caller Finder ===");
        System.out.println("Target Class: " + fullyQualifiedClassName);
        System.out.println("Line Number: " + lineNumber);
        System.out.println("Source Root: " + sourceRoot);
        System.out.println("Package Prefix: " + packagePrefix);
        System.out.println("==============================\n");
        
        try {
            EnhancedMethodCallerFinderOlder finder = new EnhancedMethodCallerFinderOlder(sourceRoot, packagePrefix);
            finder.findCallerChains(sourceRoot, fullyQualifiedClassName, lineNumber);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}