package me.dreamhopping.pml.runtime.start.auth.util;

public class TimeUtils {
    public static long secondsSinceEpoch() {
        return System.currentTimeMillis() / 1000;
    }
}
