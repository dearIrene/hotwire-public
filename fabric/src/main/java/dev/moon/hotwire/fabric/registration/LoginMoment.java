package dev.moon.hotwire.fabric.registration;

import java.lang.reflect.Method;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

public final class LoginMoment {
    public static final String NAME = findName();

    private LoginMoment() {
    }

    private static String findName() {
        for (Method method : ClientPacketListener.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ClientboundLoginPacket.class
                    && method.getReturnType() == void.class) {
                return method.getName();
            }
        }
        return "handleLogin";
    }
}
