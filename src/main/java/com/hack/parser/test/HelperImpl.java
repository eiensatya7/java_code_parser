package com.hack.parser.test;

import lombok.extern.slf4j.Slf4j;

/**
 * A sample class to test the Java parser functionality
 */
@Slf4j
public class HelperImpl implements Helper {

    private InnerHelper innerHelper;


    @Override
    public void helperMethod() {
        log.info("In helper method");
    }

    @Override
    public void helperMethod(int a) {
        log.info("In helper method"+a);
        innerHelper.innerHelperMethod(8,9);
    }

    @Override
    public void helperMethod(int a, int b) {
        log.info("In helper method");
    }

}