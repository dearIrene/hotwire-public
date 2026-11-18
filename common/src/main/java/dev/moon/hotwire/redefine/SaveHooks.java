package dev.moon.hotwire.redefine;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SaveHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(SaveHooks.class);

    private static final Set<String> SAVE_METHODS = Set.of("saveWithoutId", "load", "addAdditionalSaveData",
            "readAdditionalSaveData", "save", "saveAsPassenger", "saveAdditional", "loadAdditional",
            "loadWithComponents", "loadCustomOnly", "saveWithFullMetadata", "saveWithoutMetadata", "saveCustomOnly",
            "saveWithId", "saveCustomAndMetadata", "saveMetadata",
            "method_5647", "method_5651", "method_5652", "method_5749", "method_5662", "method_5786",
            "method_11007", "method_11014", "method_58690", "method_58691", "method_38242", "method_71399",
            "method_38244", "method_71400", "method_58692", "method_71401", "method_38243", "method_59535",
            "method_10999", "method_14943");

    private static final Set<String> SAVED_TYPES = Set.of("net/minecraft/nbt/CompoundTag",
            "net/minecraft/world/level/storage/ValueInput", "net/minecraft/world/level/storage/ValueOutput",
            "net/minecraft/class_2487", "net/minecraft/class_11368", "net/minecraft/class_11372");
    private static final MethodHandle RUN;

    static {
        try {
            RUN = MethodHandles.lookup().findStatic(SaveHooks.class, "run", MethodType.methodType(Object.class,
                    MethodHandle.class, MethodHandle.class, String.class, AtomicBoolean.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private SaveHooks() {
    }

    public static boolean isSaveMethod(String name, String desc) {
        if (!SAVE_METHODS.contains(name)) {
            return false;
        }
        for (Type type : Type.getArgumentTypes(desc)) {
            if (type.getSort() == Type.OBJECT && SAVED_TYPES.contains(type.getInternalName())) {
                return true;
            }
        }
        Type returned = Type.getReturnType(desc);
        return returned.getSort() == Type.OBJECT && SAVED_TYPES.contains(returned.getInternalName());
    }

    static MethodHandle wrap(MethodHandle handler, MethodHandle standIn, String mixin) {
        MethodType type = handler.type();
        return MethodHandles.insertArguments(RUN, 0, handler, standIn, mixin, new AtomicBoolean())
                .asCollector(Object[].class, type.parameterCount()).asType(type);
    }

    private static @Nullable Object run(MethodHandle handler, MethodHandle standIn, String mixin, AtomicBoolean said,
            Object[] arguments) throws Throwable {
        boolean before = Tripwire.markHotwireWork(true);
        try {
            return handler.invokeWithArguments(arguments);
        } catch (RuntimeException | LinkageError e) {
            if (said.compareAndSet(false, true)) {
                LOGGER.warn("[Hotwire] {}'s save hook threw while its mod is off, and is skipped: {}", mixin,
                        e.toString());
            }
        } finally {
            Tripwire.markHotwireWork(before);
        }
        return standIn.invokeWithArguments(arguments);
    }

    static void noteRun(String internalName, String mixin) {
        ClassState.of(internalName).addSaveRun(mixin);
    }

    static void noteLost(String internalName, String mixin, String why) {
        ClassState.of(internalName).addSaveLost(mixin, why);
    }

    public static Map<String, String> findStopped(Collection<String> mods) {
        Map<String, String> found = new TreeMap<>();
        if (mods.isEmpty()) {
            return found;
        }
        ClassState.visitAll((internalName, state) -> {
            state.getSavesLost().forEach((mixin, why) -> addIfOf(mods, mixin, why + " in " + internalName, found));
            Map<String, String> whys = state.getUnflippableWhy();
            for (String mixin : state.getSavesRun()) {
                if (state.getUnflippable().contains(mixin) && !HandlerGuards.IN_CONSTRUCTOR.equals(whys.get(mixin))) {
                    addIfOf(mods, mixin, internalName + " is rebuilt without it", found);
                }
            }
        });
        return found;
    }

    private static void addIfOf(Collection<String> mods, String mixin, String why, Map<String, String> found) {
        String mod = Linker.findMixinOwner(mixin.replace('.', '/'));
        if (mod != null && mods.contains(mod)) {
            found.putIfAbsent(mod, mixin + " (" + why + ")");
        }
    }
}
