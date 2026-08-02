package net.ngk22.bobbyshare.config;

import java.util.ArrayList;
import java.util.List;

public class BobbyShareConfig {
    public double rateLimitBurst = 200.0;
    public double rateLimitRefill = 80.0;
    public int cacheCapacity = 4096;
    public double maxRequestDistance = 34.0;
    public List<String> blacklistedDimensions = new ArrayList<>();

    public BobbyShareConfig() { blacklistedDimensions.add("minecraft:the_end"); }
}
