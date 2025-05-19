package com.hack.parser.test;

/**
 * A sample class to test the Java parser functionality
 */
public class HelperImpl implements Helper {

    @Override
    public void helperMethod() {
        System.out.println("In helper method");
    }

    @Override
    public void helperMethod(int a) {
        System.out.println("In helper method"+a);
    }

    @Override
    public void helperMethod(int a, int b) {
        System.out.println("In helper method");
    }

}