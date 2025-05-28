package com.hack.parser.test;

import lombok.extern.slf4j.Slf4j;

/**
 * A sample class to test the Java parser functionality
 */
@Slf4j
public class TestSample {

    private Helper helper = new HelperImpl2();
    /**
     * Main method that starts the execution, ok testing some things here
     */
    public static void main(String[] args) {
        TestSample sample = new TestSample();
        log.info("Starting TestSample execution");
        sample.methodA();
    }

    /**
     * First method in the call chain
     */
    public void methodA() {
        log.info("In method A");
        methodB(42);
    }

    /**
     * Second method in the call chain
     */
    public void methodB(int value) {
        log.info("In method B with value: {}", value);
        methodC("test");
    }

    /**
     * Third method in the call chain
     */
    public void methodC(String text) {
        log.info("In method C with text: {}", text);

        // Create an object and call its method
//        HelperImpl helperImpl = new HelperImpl();
        helper.helperMethod(0);
    }

}