package com.hack.parser.test;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InnerHelperImpl implements InnerHelper {

    @Override
    public void innerHelperMethod() {

    }

    @Override
    public void innerHelperMethod(int a) {

    }

    @Override
    public void innerHelperMethod(int a, int b) {
        log.info("InnerHelperImpl innerHelperMethod(int a, int b) called");
    }

    @Override
    public void innerHelperMethod(int a, int b, int c) {

    }

    @Override
    public void innerHelperMethod(int a, int b, int c, int d) {

    }
}
