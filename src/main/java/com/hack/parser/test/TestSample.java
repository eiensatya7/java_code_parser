package com.hack.parser.test;

/**
 * A sample class to test the Java parser functionality
 */
public class TestSample {

    private Helper helper;
    /**
     * Main method that starts the execution
     */
    public static void main(String[] args) {
        TestSample sample = new TestSample();
        sample.methodA();
    }

    /**
     * First method in the call chain
     */
    public void methodA() {
        System.out.println("In method A");
        methodB(42);
    }

    /**
     * Second method in the call chain
     */
    public void methodB(int value) {
        System.out.println("In method B with value: " + value);
        methodC("test");
    }

    /**
     * Third method in the call chain
     */
    public void methodC(String text) {
        System.out.println("In method C with text: " + text);

        // Create an object and call its method
//        HelperImpl helperImpl = new HelperImpl();
        helper.helperMethod(9);
    }

}