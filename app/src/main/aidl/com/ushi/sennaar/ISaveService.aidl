package com.ushi.sennaar;

interface ISaveService {
    void destroy() = 16777114;
    String capture(String device, @nullable String slot) = 0;
    void restore(String snapshot, String expectedFingerprint) = 1;
    String[] listSlots() = 2;
}
