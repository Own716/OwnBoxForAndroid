package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class BalancerBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;

    public static final String STRATEGY_LEAST_PING = "leastPing";
    public static final String STRATEGY_LEAST_LOAD = "leastLoad";
    public static final String STRATEGY_RANDOM = "random";
    public static final String STRATEGY_ROUND_ROBIN = "roundRobin";
    public static final String STRATEGY_ROUND_ROBIN_LEGACY = "round_robin";
    public static final String STRATEGY_FAILOVER = "failover";
    public static final String STRATEGY_STABLE = "stable";
    public static final String STRATEGY_CONSISTENT_HASH = "consistent_hash";
    public static final String STRATEGY_CONSISTENT_HASH_CAMEL = "consistentHash";
    public static final String STRATEGY_AUTO_SELECTOR = "autoSelector";

    public static final int MAX_POOL_CAP = 3000;
    public static final int MAX_BUILD_LIMIT = 500;

    public int balancerType = TYPE_LIST; // 0 = list, 1 = group
    public long targetGroupId = 0L;
    public List<Long> targetGroupIds = new ArrayList<>();
    public List<Long> proxies = new ArrayList<>();
    public String strategy = STRATEGY_LEAST_PING;
    public String testUrl = "";
    public int interval = 300;
    public int tolerance = 50;
    public String toleranceUnit = "ms";
    // v4 fields
    public boolean useFrontProxy = false;
    public boolean useLandingProxy = false;
    public String nameExclude = "";
    public String nameInclude = "";
    // v5 fields
    public long frontProxy = -1L;
    public long landingProxy = -1L;
    // v6 fields (Auto Selector)
    public String countryFilter = "";
    public boolean excludeUnavailable = true;
    public int poolCap = 1000;
    public int buildLimit = 300;
    public int resultValidityMins = 1440;
    public String connectivityUrl = "";
    public int benchIntervalSec = 600;
    public int watchIntervalSec = 15;
    public int activeSize = 8;
    public int sampling = 10;
    public int maxRttMs = 0;
    public int expected = 3;
    public int dialRetries = 2;
    public boolean interruptOnSwitch = false;
    public boolean balance = false;
    public String balanceMode = "rotate";
    public int balanceIntervalSec = 30;
    public long pinnedId = -1L;
    public List<Long> pool = new ArrayList<>();
    public long poolRankedAt = 0L;
    public List<Long> lastBuilt = new ArrayList<>();
    public long lastBuiltAt = 0L;
    public String historyJson = "";

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
        String stratName;
        if (STRATEGY_LEAST_PING.equals(strategy)) {
            stratName = "最低延迟";
        } else if (STRATEGY_LEAST_LOAD.equals(strategy)) {
            stratName = "最低负载";
        } else if (STRATEGY_RANDOM.equals(strategy)) {
            stratName = "随机选择";
        } else if (STRATEGY_ROUND_ROBIN.equals(strategy) || STRATEGY_ROUND_ROBIN_LEGACY.equals(strategy)) {
            stratName = "轮询";
        } else if (STRATEGY_FAILOVER.equals(strategy)) {
            stratName = "故障转移";
        } else if (STRATEGY_STABLE.equals(strategy)) {
            stratName = "最稳定";
        } else if (STRATEGY_CONSISTENT_HASH.equals(strategy) || STRATEGY_CONSISTENT_HASH_CAMEL.equals(strategy)) {
            stratName = "一致性哈希";
        } else if (STRATEGY_AUTO_SELECTOR.equals(strategy)) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int pCount = proxies != null ? proxies.size() : 0;
            if (gCount > 0 && pCount > 0) {
                return "分组 " + gCount + " · 节点 " + pCount + " · 自动选择";
            } else if (gCount > 0) {
                return "分组 " + gCount + " · 自动选择";
            } else if (pCount > 0) {
                return "节点 " + pCount + " · 自动选择";
            }
            return "自动选择";
        } else {
            stratName = strategy != null ? strategy : STRATEGY_LEAST_PING;
        }
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "分组 " + gCount + (stratName != null && !stratName.isEmpty() ? " · " + stratName : "");
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "节点 " + count + (stratName != null && !stratName.isEmpty() ? " · " + stratName : "");
        }
    }

    public String displayNodeOnly() {
        if (STRATEGY_AUTO_SELECTOR.equals(strategy)) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int pCount = proxies != null ? proxies.size() : 0;
            if (gCount > 0 && pCount > 0) return "分组 " + gCount + " · 节点 " + pCount;
            if (gCount > 0) return "分组 " + gCount;
            if (pCount > 0) return "节点 " + pCount;
            return "自动选择";
        }
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "分组 " + gCount;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "节点 " + count;
        }
    }

    public String displayWithNodeName(String nodeName) {
        if (STRATEGY_AUTO_SELECTOR.equals(strategy)) {
            String base = displayNodeOnly();
            if (nodeName != null && !nodeName.trim().isEmpty()) {
                return base + " · " + nodeName.trim();
            }
            return base;
        }
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            if (nodeName != null && !nodeName.trim().isEmpty()) {
                return "分组 " + gCount + " · " + nodeName.trim();
            }
            return "分组 " + gCount;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            if (nodeName != null && !nodeName.trim().isEmpty()) {
                return "节点 " + count + " · " + nodeName.trim();
            }
            return "节点 " + count;
        }
    }

    private static List<Long> dedupPositiveIds(List<Long> input) {
        List<Long> out = new ArrayList<>();
        if (input == null) return out;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (Long id : input) {
            if (id != null && id > 0L && seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public void normalizeAutoSelector() {
        targetGroupIds = dedupPositiveIds(targetGroupIds);
        if (targetGroupIds.isEmpty() && targetGroupId > 0L) {
            targetGroupIds.add(targetGroupId);
        }
        targetGroupId = targetGroupIds.isEmpty() ? 0L : targetGroupIds.get(0);
        proxies = dedupPositiveIds(proxies);
        pool = dedupPositiveIds(pool);
        lastBuilt = dedupPositiveIds(lastBuilt);
        if (poolCap < 1) poolCap = 1;
        if (poolCap > MAX_POOL_CAP) poolCap = MAX_POOL_CAP;
        if (buildLimit < 1) buildLimit = 1;
        if (buildLimit > MAX_BUILD_LIMIT) buildLimit = MAX_BUILD_LIMIT;
        if (buildLimit > poolCap) buildLimit = poolCap;
        if (resultValidityMins < 0) resultValidityMins = 0;
        if (interval < 10) interval = 10;
        if (benchIntervalSec < interval) benchIntervalSec = interval;
        if (watchIntervalSec < 5) watchIntervalSec = 5;
        if (watchIntervalSec > interval) watchIntervalSec = interval;
        if (activeSize < 1) activeSize = 1;
        if (expected < 1) expected = 1;
        if (activeSize < expected) activeSize = expected;
        if (activeSize > buildLimit) activeSize = buildLimit;
        if (sampling < 2) sampling = 2;
        if (sampling > 60) sampling = 60;
        if (tolerance < 0) tolerance = 0;
        if (toleranceUnit == null || toleranceUnit.isEmpty()) toleranceUnit = "ms";
        if (maxRttMs < 0) maxRttMs = 0;
        if (expected > buildLimit) expected = buildLimit;
        if (dialRetries < 0) dialRetries = 0;
        if (dialRetries > 5) dialRetries = 5;
        if (balanceIntervalSec < 5) balanceIntervalSec = 5;
        if (!"connection".equals(balanceMode)) balanceMode = "rotate";
        if (countryFilter == null) countryFilter = "";
        if (connectivityUrl == null) connectivityUrl = "";
        if (historyJson == null) historyJson = "";
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (proxies == null) proxies = new ArrayList<>();
        if (targetGroupIds == null) targetGroupIds = new ArrayList<>();
        if (strategy == null || strategy.isEmpty()) strategy = STRATEGY_LEAST_PING;
        if (testUrl == null) testUrl = "";
        if (interval <= 0) interval = 300;
        if (tolerance < 0) tolerance = 300;
        if (toleranceUnit == null || toleranceUnit.isEmpty()) toleranceUnit = "ms";
        if (nameExclude == null) nameExclude = "";
        if (nameInclude == null) nameInclude = "";
        if (frontProxy == 0L) frontProxy = -1L;
        if (landingProxy == 0L) landingProxy = -1L;
        if (countryFilter == null) countryFilter = "";
        if (connectivityUrl == null) connectivityUrl = "";
        if (poolCap <= 0) poolCap = 1000;
        if (buildLimit <= 0) buildLimit = 300;
        if (resultValidityMins < 0) resultValidityMins = 1440;
        if (benchIntervalSec <= 0) benchIntervalSec = 600;
        if (watchIntervalSec <= 0) watchIntervalSec = 15;
        if (activeSize <= 0) activeSize = 8;
        if (sampling <= 0) sampling = 10;
        if (maxRttMs < 0) maxRttMs = 0;
        if (expected <= 0) expected = 3;
        if (dialRetries < 0) dialRetries = 2;
        if (balanceMode == null || balanceMode.isEmpty()) balanceMode = "rotate";
        if (balanceIntervalSec <= 0) balanceIntervalSec = 30;
        if (pinnedId == 0L) pinnedId = -1L;
        if (pool == null) pool = new ArrayList<>();
        if (lastBuilt == null) lastBuilt = new ArrayList<>();
        if (historyJson == null) historyJson = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(6); // version
        output.writeInt(balancerType);
        output.writeLong(targetGroupId);
        output.writeString(strategy);
        output.writeString(testUrl);
        output.writeInt(interval);

        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy);
        }

        if (targetGroupIds == null) {
            targetGroupIds = new ArrayList<>();
        }
        output.writeInt(targetGroupIds.size());
        for (Long gid : targetGroupIds) {
            output.writeLong(gid);
        }
        output.writeInt(tolerance);
        output.writeString(toleranceUnit != null ? toleranceUnit : "ms");
        // v4 fields
        output.writeBoolean(useFrontProxy);
        output.writeBoolean(useLandingProxy);
        output.writeString(nameExclude != null ? nameExclude : "");
        output.writeString(nameInclude != null ? nameInclude : "");
        // v5 fields
        output.writeLong(frontProxy);
        output.writeLong(landingProxy);
        // v6 fields
        output.writeString(countryFilter != null ? countryFilter : "");
        output.writeBoolean(excludeUnavailable);
        output.writeInt(poolCap);
        output.writeInt(buildLimit);
        output.writeInt(resultValidityMins);
        output.writeString(connectivityUrl != null ? connectivityUrl : "");
        output.writeInt(benchIntervalSec);
        output.writeInt(watchIntervalSec);
        output.writeInt(activeSize);
        output.writeInt(sampling);
        output.writeInt(maxRttMs);
        output.writeInt(expected);
        output.writeInt(dialRetries);
        output.writeBoolean(interruptOnSwitch);
        output.writeBoolean(balance);
        output.writeString(balanceMode != null ? balanceMode : "rotate");
        output.writeInt(balanceIntervalSec);
        output.writeLong(pinnedId);
        if (pool == null) pool = new ArrayList<>();
        output.writeInt(pool.size());
        for (Long id : pool) {
            output.writeLong(id != null ? id : 0L);
        }
        output.writeLong(poolRankedAt);
        if (lastBuilt == null) lastBuilt = new ArrayList<>();
        output.writeInt(lastBuilt.size());
        for (Long id : lastBuilt) {
            output.writeLong(id != null ? id : 0L);
        }
        output.writeLong(lastBuiltAt);
        output.writeString(historyJson != null ? historyJson : "");
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        if (version >= 1) {
            balancerType = input.readInt();
            targetGroupId = input.readLong();
            strategy = input.readString();
            testUrl = input.readString();
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
            } else {
                tolerance = 300;
                toleranceUnit = "ms";
            }

            if (version >= 4) {
                useFrontProxy = input.readBoolean();
                useLandingProxy = input.readBoolean();
                nameExclude = input.readString();
                nameInclude = input.readString();
            } else {
                useFrontProxy = false;
                useLandingProxy = false;
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
                countryFilter = input.readString();
                excludeUnavailable = input.readBoolean();
                poolCap = input.readInt();
                buildLimit = input.readInt();
                resultValidityMins = input.readInt();
                connectivityUrl = input.readString();
                benchIntervalSec = input.readInt();
                watchIntervalSec = input.readInt();
                activeSize = input.readInt();
                sampling = input.readInt();
                maxRttMs = input.readInt();
                expected = input.readInt();
                dialRetries = input.readInt();
                interruptOnSwitch = input.readBoolean();
                balance = input.readBoolean();
                balanceMode = input.readString();
                balanceIntervalSec = input.readInt();
                pinnedId = input.readLong();
                int pLen = input.readInt();
                pool = new ArrayList<>(Math.max(0, pLen));
                for (int i = 0; i < pLen; i++) {
                    pool.add(input.readLong());
                }
                poolRankedAt = input.readLong();
                int bLen = input.readInt();
                lastBuilt = new ArrayList<>(Math.max(0, bLen));
                for (int i = 0; i < bLen; i++) {
                    lastBuilt.add(input.readLong());
                }
                lastBuiltAt = input.readLong();
                historyJson = input.readString();
            } else {
                countryFilter = "";
                excludeUnavailable = true;
                poolCap = 1000;
                buildLimit = 300;
                resultValidityMins = 1440;
                connectivityUrl = "";
                benchIntervalSec = 600;
                watchIntervalSec = 15;
                activeSize = 8;
                sampling = 10;
                maxRttMs = 0;
                expected = 3;
                dialRetries = 2;
                interruptOnSwitch = false;
                balance = false;
                balanceMode = "rotate";
                balanceIntervalSec = 30;
                pinnedId = -1L;
                pool = new ArrayList<>();
                poolRankedAt = 0L;
                lastBuilt = new ArrayList<>();
                lastBuiltAt = 0L;
                historyJson = "";
            }
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
