package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class BalancerBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;
    public static final int TYPE_MIXED = 2;

    public static final String STRATEGY_LEAST_PING = "leastPing";
    public static final String STRATEGY_LEAST_LOAD = "leastLoad";
    public static final String STRATEGY_RANDOM = "random";
    public static final String STRATEGY_ROUND_ROBIN = "roundRobin";
    public static final String STRATEGY_ROUND_ROBIN_LEGACY = "round_robin";
    public static final String STRATEGY_FAILOVER = "failover";
    public static final String STRATEGY_STABLE = "stable";
    public static final String STRATEGY_CONSISTENT_HASH = "consistent_hash";
    public static final String STRATEGY_CONSISTENT_HASH_CAMEL = "consistentHash";

    public static final String SELECTION_LATENCY = "latency";
    public static final String SELECTION_PRIORITY = "priority";
    public static final String BALANCE_TIMED_ROTATION = "timed_rotation";
    public static final String BALANCE_PER_CONNECTION = "per_connection";

    public int balancerType = TYPE_LIST; // 0 = list, 1 = group
    public long targetGroupId = 0L;
    public List<Long> targetGroupIds = new ArrayList<>();
    public List<Long> proxies = new ArrayList<>();
    public String strategy = SELECTION_LATENCY;
    public String testUrl = "";
    public int interval = 300;
    public int tolerance = 30;
    public String toleranceUnit = "ms";
    // v4 fields
    public String nameExclude = "";
    public String nameInclude = "";
    // v5 fields
    public long frontProxy = -1L;
    public long landingProxy = -1L;
    // v6 unified auto-select fields
    public String selectionMode = SELECTION_LATENCY;
    public boolean balanceEnabled = false;
    public String balanceMode = BALANCE_TIMED_ROTATION;
    public int balanceIntervalSec = 30;
    public String countryFilter = "";
    public boolean interruptOnSwitch = false;
    public boolean learnFromConnections = true;
    // v7 priority tiers
    public List<PriorityTier> tiers = new ArrayList<>();

    public static class PriorityTier {
        public long groupId = -1L;
        public String nameFilter = "";

        public PriorityTier() {
        }

        public PriorityTier(long groupId, String nameFilter) {
            this.groupId = groupId;
            this.nameFilter = nameFilter != null ? nameFilter : "";
        }

        public PriorityTier copy() {
            return new PriorityTier(groupId, nameFilter);
        }

        public boolean matchesAll() {
            return groupId <= 0L && (nameFilter == null || nameFilter.trim().isEmpty());
        }
    }

    public static String tiersToJson(List<PriorityTier> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        JsonArray arr = new JsonArray();
        for (PriorityTier t : list) {
            if (t == null) continue;
            JsonObject obj = new JsonObject();
            obj.addProperty("groupId", t.groupId);
            obj.addProperty("nameFilter", t.nameFilter != null ? t.nameFilter : "");
            arr.add(obj);
        }
        return arr.toString();
    }

    public static List<PriorityTier> parseTiersJson(String json) {
        List<PriorityTier> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return out;
            }
            for (JsonElement elem : parsed.getAsJsonArray()) {
                if (!elem.isJsonObject()) continue;
                JsonObject obj = elem.getAsJsonObject();
                long gid = obj.has("groupId") && obj.get("groupId").isJsonPrimitive()
                        ? obj.get("groupId").getAsLong()
                        : -1L;
                String filter = obj.has("nameFilter") && obj.get("nameFilter").isJsonPrimitive()
                        ? obj.get("nameFilter").getAsString()
                        : "";
                out.add(new PriorityTier(gid, filter));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public boolean isPriorityMode() {
        return SELECTION_PRIORITY.equals(selectionMode);
    }

    public long calculateToleranceMs() {
        long value = tolerance;
        if (value < 0L) {
            value = 0L;
        }
        if ("s".equalsIgnoreCase(toleranceUnit)) {
            return Math.min(value * 1000L, (long) Integer.MAX_VALUE);
        }
        return Math.min(value, (long) Integer.MAX_VALUE);
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "Balancer 01";
        }
    }

    @Override
    public String displayAddress() {
        StringBuilder stratBuilder = new StringBuilder();
        stratBuilder.append(isPriorityMode() ? "按优先级" : "最快最稳");
        if (isPriorityMode() && tiers != null && !tiers.isEmpty()) {
            stratBuilder.append(" · ").append(tiers.size()).append("层级");
        }
        if (balanceEnabled) {
            stratBuilder.append(" · 流量分配");
        }
        String stratName = stratBuilder.toString();
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "分组 " + gCount + " · " + stratName;
        } else if (balancerType == TYPE_MIXED) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int pCount = proxies != null ? proxies.size() : 0;
            return "混合 " + gCount + "+" + pCount + " · " + stratName;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "节点 " + count + " · " + stratName;
        }
    }

    public String displayNodeOnly() {
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "分组 " + gCount;
        } else if (balancerType == TYPE_MIXED) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int pCount = proxies != null ? proxies.size() : 0;
            return "混合 " + gCount + "+" + pCount;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "节点 " + count;
        }
    }

    public String displayWithNodeName(String nodeName) {
        String prefix;
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            prefix = "分组 " + gCount;
        } else if (balancerType == TYPE_MIXED) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int pCount = proxies != null ? proxies.size() : 0;
            prefix = "混合 " + gCount + "+" + pCount;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            prefix = "节点 " + count;
        }
        if (nodeName != null && !nodeName.trim().isEmpty()) {
            return prefix + " · " + nodeName.trim();
        }
        return prefix;
    }

    private static String nonNullString(String value) {
        return value != null ? value : "";
    }

    private void resetActiveStrategyDefaults() {
        selectionMode = SELECTION_LATENCY;
        balanceEnabled = false;
        balanceMode = BALANCE_TIMED_ROTATION;
        balanceIntervalSec = 30;
        countryFilter = "";
        interruptOnSwitch = false;
        learnFromConnections = true;
        tiers = new ArrayList<>();
    }

    private void migrateLegacyStrategy(String rawStrategy) {
        resetActiveStrategyDefaults();
        String norm = rawStrategy != null ? rawStrategy.trim() : "";
        if (STRATEGY_FAILOVER.equals(norm) || SELECTION_PRIORITY.equals(norm)) {
            selectionMode = SELECTION_PRIORITY;
            balanceEnabled = false;
            balanceMode = BALANCE_TIMED_ROTATION;
        } else if (STRATEGY_ROUND_ROBIN.equals(norm)
                || STRATEGY_ROUND_ROBIN_LEGACY.equals(norm)
                || STRATEGY_RANDOM.equals(norm)
                || STRATEGY_LEAST_LOAD.equals(norm)) {
            selectionMode = SELECTION_LATENCY;
            balanceEnabled = true;
            balanceMode = BALANCE_PER_CONNECTION;
        } else if (STRATEGY_CONSISTENT_HASH.equals(norm)
                || STRATEGY_CONSISTENT_HASH_CAMEL.equals(norm)) {
            selectionMode = SELECTION_LATENCY;
            balanceEnabled = true;
            balanceMode = BALANCE_TIMED_ROTATION;
            learnFromConnections = true;
        } else {
            selectionMode = SELECTION_LATENCY;
            balanceEnabled = false;
            balanceMode = BALANCE_TIMED_ROTATION;
        }
        strategy = selectionMode;
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (proxies == null) proxies = new ArrayList<>();
        if (targetGroupIds == null) targetGroupIds = new ArrayList<>();
        if (tiers == null) tiers = new ArrayList<>();
        if (selectionMode == null || selectionMode.isEmpty()) {
            selectionMode = SELECTION_PRIORITY.equals(strategy) || STRATEGY_FAILOVER.equals(strategy)
                    ? SELECTION_PRIORITY
                    : SELECTION_LATENCY;
        } else if (!SELECTION_LATENCY.equals(selectionMode) && !SELECTION_PRIORITY.equals(selectionMode)) {
            selectionMode = SELECTION_LATENCY;
        }
        strategy = selectionMode;
        if (balanceMode == null || (!BALANCE_TIMED_ROTATION.equals(balanceMode) && !BALANCE_PER_CONNECTION.equals(balanceMode))) {
            balanceMode = BALANCE_TIMED_ROTATION;
        }
        if (balanceIntervalSec <= 0) balanceIntervalSec = 30;
        if (countryFilter == null) countryFilter = "";
        if (testUrl == null) testUrl = "";
        if (interval <= 0) interval = 300;
        if (tolerance < 0) tolerance = 30;
        if (toleranceUnit == null || toleranceUnit.isEmpty()) toleranceUnit = "ms";
        if (nameExclude == null) nameExclude = "";
        if (nameInclude == null) nameInclude = "";
        if (frontProxy == 0L) frontProxy = -1L;
        if (landingProxy == 0L) landingProxy = -1L;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        String effectiveSelection = SELECTION_PRIORITY.equals(selectionMode) ? SELECTION_PRIORITY : SELECTION_LATENCY;
        strategy = effectiveSelection;

        output.writeInt(8); // version
        output.writeInt(balancerType);
        output.writeLong(targetGroupId);
        output.writeString(strategy);
        output.writeString(nonNullString(testUrl));
        output.writeInt(interval);

        if (proxies == null) {
            proxies = new ArrayList<>();
        }
        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy != null ? proxy : 0L);
        }

        if (targetGroupIds == null) {
            targetGroupIds = new ArrayList<>();
        }
        output.writeInt(targetGroupIds.size());
        for (Long gid : targetGroupIds) {
            output.writeLong(gid != null ? gid : 0L);
        }
        output.writeInt(tolerance);
        output.writeString(toleranceUnit != null ? toleranceUnit : "ms");
        output.writeString(nonNullString(nameExclude));
        output.writeString(nonNullString(nameInclude));
        output.writeLong(frontProxy);
        output.writeLong(landingProxy);
        output.writeString(effectiveSelection);
        output.writeBoolean(balanceEnabled);
        output.writeString(balanceMode != null ? balanceMode : BALANCE_TIMED_ROTATION);
        output.writeInt(balanceIntervalSec);
        output.writeString(nonNullString(countryFilter));
        output.writeBoolean(interruptOnSwitch);
        output.writeBoolean(learnFromConnections);

        if (tiers == null) {
            tiers = new ArrayList<>();
        }
        output.writeInt(tiers.size());
        for (PriorityTier tier : tiers) {
            output.writeLong(tier != null ? tier.groupId : -1L);
            output.writeString(tier != null && tier.nameFilter != null ? tier.nameFilter : "");
        }
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        if (version >= 1) {
            balancerType = input.readInt();
            targetGroupId = input.readLong();
            strategy = input.readString();
            testUrl = nonNullString(input.readString());
            interval = input.readInt();

            int length = input.readInt();
            proxies = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                proxies.add(input.readLong());
            }

            targetGroupIds = new ArrayList<>();
            if (version >= 2) {
                int gCount = input.readInt();
                for (int i = 0; i < gCount; i++) {
                    targetGroupIds.add(input.readLong());
                }
            } else if (targetGroupId > 0L) {
                targetGroupIds.add(targetGroupId);
            }

            if (version >= 3) {
                tolerance = input.readInt();
                toleranceUnit = input.readString();
                if (toleranceUnit == null || toleranceUnit.isEmpty()) {
                    toleranceUnit = "ms";
                }
            } else {
                tolerance = 50;
                toleranceUnit = "ms";
            }

            if (version >= 8) {
                nameExclude = nonNullString(input.readString());
                nameInclude = nonNullString(input.readString());
                frontProxy = input.readLong();
                landingProxy = input.readLong();
                selectionMode = input.readString();
                balanceEnabled = input.readBoolean();
                balanceMode = input.readString();
                if (!BALANCE_PER_CONNECTION.equals(balanceMode)) {
                    balanceMode = BALANCE_TIMED_ROTATION;
                }
                balanceIntervalSec = input.readInt();
                countryFilter = nonNullString(input.readString());
                interruptOnSwitch = input.readBoolean();
                learnFromConnections = input.readBoolean();
                if (!SELECTION_PRIORITY.equals(selectionMode)) {
                    selectionMode = SELECTION_LATENCY;
                }
                strategy = selectionMode;
                readTiers(input);
                return;
            }

            if (version >= 4) {
                input.readBoolean(); // legacy useFrontProxy
                input.readBoolean(); // legacy useLandingProxy
                nameExclude = nonNullString(input.readString());
                nameInclude = nonNullString(input.readString());
            } else {
                nameExclude = "";
                nameInclude = "";
            }

            if (version >= 5) {
                frontProxy = input.readLong();
                landingProxy = input.readLong();
            } else {
                frontProxy = -1L;
                landingProxy = -1L;
            }

            if (version >= 6) {
                selectionMode = input.readString();
                balanceEnabled = input.readBoolean();
                balanceMode = input.readString();
                if (!BALANCE_PER_CONNECTION.equals(balanceMode)) {
                    balanceMode = BALANCE_TIMED_ROTATION;
                }
                balanceIntervalSec = input.readInt();
                countryFilter = nonNullString(input.readString());
                input.readInt(); // legacy runTopN
                input.readInt(); // legacy rankTopN
                input.readInt(); // legacy resultTrustMinutes
                input.readBoolean(); // legacy skipFailedWithinTrust
                input.readInt(); // legacy standbySize
                input.readInt(); // legacy intensiveCheckCount
                input.readInt(); // legacy fullScanIntervalSec
                input.readInt(); // legacy watchIntervalSec
                input.readInt(); // legacy sampleCount
                input.readInt(); // legacy maxRttMs
                input.readInt(); // legacy dialRetries
                interruptOnSwitch = input.readBoolean();
                input.readInt(); // legacy failbackChecks
                input.readInt(); // legacy failbackDelaySec
                input.readBoolean(); // legacy interruptOnFailback
                learnFromConnections = input.readBoolean();
                input.readInt(); // legacy siteStickyMinutes
                input.readString(); // legacy connectivityUrl
                if (!SELECTION_PRIORITY.equals(selectionMode)) {
                    selectionMode = SELECTION_LATENCY;
                }
                strategy = selectionMode;
            } else {
                migrateLegacyStrategy(strategy);
            }

            tiers = new ArrayList<>();
            if (version >= 7) {
                readTiers(input);
            }
        }
    }

    private void readTiers(ByteBufferInput input) {
        tiers = new ArrayList<>();
        int tCount = input.readInt();
        for (int i = 0; i < tCount; i++) {
            long gId = input.readLong();
            String filter = nonNullString(input.readString());
            tiers.add(new PriorityTier(gId, filter));
        }
    }

    @NotNull
    @Override
    public BalancerBean clone() {
        return KryoConverters.deserialize(new BalancerBean(), KryoConverters.serialize(this));
    }

    public static final Creator<BalancerBean> CREATOR = new CREATOR<BalancerBean>() {
        @NonNull
        @Override
        public BalancerBean newInstance() {
            return new BalancerBean();
        }

        @Override
        public BalancerBean[] newArray(int size) {
            return new BalancerBean[size];
        }
    };
}
