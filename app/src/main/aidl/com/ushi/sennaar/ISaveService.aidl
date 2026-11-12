package com.ushi.sennaar;

interface ISaveService {
    void destroy() = 16777114;
    String capture(String device) = 0;
    void restore(String snapshot, String expectedFingerprint) = 1;
}
