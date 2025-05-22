package com.hack.parser.test;

/**
 * A sample class to test the Java parser functionality
 */
public class HelperImpl2 implements Helper {

    private InnerHelper innerHelper;


    @Override
    public void helperMethod() {
        System.out.println("In helper impl 2 method");
    }

    @Override
    public void helperMethod(int a) {
        System.out.println("In helper impl 2 method"+a);
        innerHelper.innerHelperMethod(8,9);
    }

    @Override
    public void helperMethod(int a, int b) {
        System.out.println("In helper impl 2 method");
    }

}