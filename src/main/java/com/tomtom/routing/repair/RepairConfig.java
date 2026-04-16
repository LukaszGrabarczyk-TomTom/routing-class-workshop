package com.tomtom.routing.repair;

public class RepairConfig {

    private final int maxBridgeHops;
    private final int maxPromotions;
    private final int maxRcJump;
    private final int[] rcLevelsToProcess;
    private final boolean enableDirectedPass;

    private RepairConfig(Builder builder) {
        this.maxBridgeHops = builder.maxBridgeHops;
        this.maxPromotions = builder.maxPromotions;
        this.maxRcJump = builder.maxRcJump;
        this.rcLevelsToProcess = builder.rcLevelsToProcess.clone();
        this.enableDirectedPass = builder.enableDirectedPass;
    }

    public int maxBridgeHops() { return maxBridgeHops; }
    public int maxPromotions() { return maxPromotions; }
    public int maxRcJump() { return maxRcJump; }
    public int[] rcLevelsToProcess() { return rcLevelsToProcess.clone(); }
    public boolean enableDirectedPass() { return enableDirectedPass; }

    public static Builder builder() { return new Builder(); }
    public static RepairConfig defaults() { return new Builder().build(); }

    public static class Builder {
        private int maxBridgeHops = 10;
        private int maxPromotions = 5;
        private int maxRcJump = 2;
        private int[] rcLevelsToProcess = {1, 2, 3, 4, 5};
        private boolean enableDirectedPass = false;

        public Builder maxBridgeHops(int v) { this.maxBridgeHops = v; return this; }
        public Builder maxPromotions(int v) { this.maxPromotions = v; return this; }
        public Builder maxRcJump(int v) { this.maxRcJump = v; return this; }
        public Builder rcLevelsToProcess(int... v) { this.rcLevelsToProcess = v.clone(); return this; }
        public Builder enableDirectedPass(boolean v) { this.enableDirectedPass = v; return this; }
        public RepairConfig build() { return new RepairConfig(this); }
    }
}
