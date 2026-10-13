package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.fabric.mixin.core.fabric.networking.GlobalReceiverRegistryAccessor;
import dev.moon.hotwire.registration.Registration;
import net.minecraft.resources.Identifier;

final class ReceiverRegistration implements Registration {
    private final GlobalReceiverRegistryAccessor registry;
    private final Identifier channel;
    private final Object handler;

    ReceiverRegistration(Object registry, Object channel, Object handler) {
        this.registry = (GlobalReceiverRegistryAccessor) registry;
        this.channel = (Identifier) channel;
        this.handler = handler;
    }

    @Override
    public String getKind() {
        return "packet receiver";
    }

    @Override
    public String describe() {
        return this.channel.toString();
    }

    @Override
    public void remove() {
        this.registry.hotwire$unregister(this.channel);
    }

    @Override
    public void restore() {
        this.registry.hotwire$register(this.channel, this.handler);
    }
}
