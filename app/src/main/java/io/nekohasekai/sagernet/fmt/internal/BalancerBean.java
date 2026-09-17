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

    public static final String STRATEGY_RANDOM = "random";
    public static final String STRATEGY_LEAST_PING = "leastPing";
    public static final String STRATEGY_LEAST_LOAD = "leastLoad";
    public static final String STRATEGY_CONSISTENT_HASH = "consistent_hash";

    public int balancerType = TYPE_LIST; // 0 = list, 1 = group
    public long targetGroupId = 0L;
    public List<Long> targetGroupIds = new ArrayList<>();
    public List<Long> proxies = new ArrayList<>();
    public String strategy = STRATEGY_RANDOM;
    public String testUrl = "";
    public int interval = 300;
    public int tolerance = 300;
    public String toleranceUnit = "ms";
    // v4 fields
    public boolean useFrontProxy = false;
    public boolean useLandingProxy = false;
    public String nameExclude = "";
    public String nameInclude = "";

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
        String stratStr = strategy != null ? strategy : STRATEGY_RANDOM;
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "[分组 (" + gCount + ")] 策略: " + stratStr;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "[列表 (" + count + ")] 策略: " + stratStr;
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (proxies == null) proxies = new ArrayList<>();
        if (targetGroupIds == null) targetGroupIds = new ArrayList<>();
        if (strategy == null || strategy.isEmpty()) strategy = STRATEGY_RANDOM;
        if (testUrl == null) testUrl = "";
        if (interval <= 0) interval = 300;
        if (tolerance < 0) tolerance = 300;
        if (toleranceUnit == null || toleranceUnit.isEmpty()) toleranceUnit = "ms";
        if (nameExclude == null) nameExclude = "";
        if (nameInclude == null) nameInclude = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4); // version
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
