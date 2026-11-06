package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WireGuardBean extends AbstractBean {

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public Integer mtu;
    public String reserved;
    public Integer listenPort;
    public Integer persistentKeepaliveInterval;

    // AmneziaWG (AWG) Obfuscation parameters
    public Integer jc;
    public Integer jmin;
    public Integer jmax;
    public Integer s1;
    public Integer s2;
    public Long h1;
    public Long h2;
    public Long h3;
    public Long h4;

    public boolean isAwg() {
        return (jc != null && jc > 0) || (s1 != null && s1 > 0) || (s2 != null && s2 > 0)
                || (h1 != null && h1 > 0) || (h2 != null && h2 > 0) || (h3 != null && h3 > 0) || (h4 != null && h4 > 0);
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (listenPort == null) listenPort = 0;
        if (persistentKeepaliveInterval == null) persistentKeepaliveInterval = 0;
        if (jc == null) jc = 0;
        if (jmin == null) jmin = 0;
        if (jmax == null) jmax = 0;
        if (s1 == null) s1 = 0;
        if (s2 == null) s2 = 0;
        if (h1 == null) h1 = 0L;
        if (h2 == null) h2 = 0L;
        if (h3 == null) h3 = 0L;
        if (h4 == null) h4 = 0L;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeInt(listenPort);
        output.writeInt(persistentKeepaliveInterval);
        output.writeInt(jc == null ? 0 : jc);
        output.writeInt(jmin == null ? 0 : jmin);
        output.writeInt(jmax == null ? 0 : jmax);
        output.writeInt(s1 == null ? 0 : s1);
        output.writeInt(s2 == null ? 0 : s2);
        output.writeLong(h1 == null ? 0L : h1);
        output.writeLong(h2 == null ? 0L : h2);
        output.writeLong(h3 == null ? 0L : h3);
        output.writeLong(h4 == null ? 0L : h4);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        mtu = input.readInt();
        reserved = input.readString();
        if (version >= 3) {
            listenPort = input.readInt();
            persistentKeepaliveInterval = input.readInt();
        } else {
            listenPort = 0;
            persistentKeepaliveInterval = 0;
        }
        if (version >= 4) {
            jc = input.readInt();
            jmin = input.readInt();
            jmax = input.readInt();
            s1 = input.readInt();
            s2 = input.readInt();
            h1 = input.readLong();
            h2 = input.readLong();
            h3 = input.readLong();
            h4 = input.readLong();
        } else {
            jc = 0;
            jmin = 0;
            jmax = 0;
            s1 = 0;
            s2 = 0;
            h1 = 0L;
            h2 = 0L;
            h3 = 0L;
            h4 = 0L;
        }
    }

    @NotNull
    @Override
    public WireGuardBean clone() {
        return KryoConverters.deserialize(new WireGuardBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WireGuardBean> CREATOR = new CREATOR<WireGuardBean>() {
        @NonNull
        @Override
        public WireGuardBean newInstance() {
            return new WireGuardBean();
        }

        @Override
        public WireGuardBean[] newArray(int size) {
            return new WireGuardBean[size];
        }
    };
}
