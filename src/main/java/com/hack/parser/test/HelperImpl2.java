package com.hack.parser.test;

import lombok.extern.slf4j.Slf4j;

/**
 * A sample class to test the Java parser functionality
 */
@Slf4j
public class HelperImpl2 implements Helper {

    private InnerHelper innerHelper = new InnerHelperImpl2();


    @Override
    public void helperMethod() {
        log.info("In helper impl 2 method");
    }

    @Override
    public void helperMethod(int a) {
        log.info("In helper impl 2 method"+a);
        innerHelper.innerHelperMethod(a,9);
    }

    @Override
    public void helperMethod(int a, int b) {
        log.info("In helper impl 2 method");
    }

}