package com.hack.parser.test;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InnerHelperImpl2 implements InnerHelper {
    @Override
    public void innerHelperMethod() {
        innerHelperMethod(1,2,3);
    }

    @Override
    public void innerHelperMethod(int a) {
        innerHelperMethod(a,2,3);

    }

    @Override
    public void innerHelperMethod(int a, int b) {
        log.info("InnerHelperImpl2 innerHelperMethod(int a, int b) called");
        innerHelperMethod(a);
    }

    @Override
    public void innerHelperMethod(int a, int b, int c) {
        log.info("InnerHelperImpl2 innerHelperMethod(int a, int b, int c) called");
        innerHelperMethod(a, 2, 3, 4);
    }

    @Override
    public void innerHelperMethod(int a, int b, int c, int d) {
        int x = 1/a;
        log.info("InnerHelperImpl2 innerHelperMethod(int a, int b, int c, int d) called" + x);
    }
}
