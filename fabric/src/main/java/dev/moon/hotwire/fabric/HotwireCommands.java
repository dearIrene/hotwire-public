package dev.moon.hotwire.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.ui.list.ModStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
*///?}
import net.minecraft.client.Minecraft;

final class HotwireCommands {
    private HotwireCommands() {
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Runnable open) {
        dispatcher.register(makeLiteral(HotwireClient.ID).executes(context -> runCommand(open))
                .then(makeLiteral("off").then(makeArgument("mod", StringArgumentType.word())
                        .executes(context -> runSwitch(StringArgumentType.getString(context, "mod"), false))))
                .then(makeLiteral("on").then(makeArgument("mod", StringArgumentType.word())
                        .executes(context -> runSwitch(StringArgumentType.getString(context, "mod"), true))))
                .then(makeLiteral("add").then(makeArgument("jar", StringArgumentType.greedyString())
                        .executes(context -> runAdd(getJar(context), false))))
                .then(makeLiteral("saveadd").then(makeArgument("jar", StringArgumentType.greedyString())
                        .executes(context -> runAdd(getJar(context), true))))
                .then(makeLiteral("update").then(makeArgument("jar", StringArgumentType.greedyString())
                        .executes(context -> runUpdate(getJar(context)))))
                .then(makeLiteral("report").executes(context -> runCommand(() -> HotwireClient.getEngine().report()))));
    }

    private static int runCommand(Runnable action) {
        action.run();
        return 1;
    }

    private static int runSwitch(String target, boolean on) {
        HotwireEngine engine = HotwireClient.getEngine();
        List<String> ids = engine.getMods().stream().map(ModStatus::id)
                .filter(id -> target.equals("all") ? ModPolicy.isSwitchable(id) && engine.getCatalog().isOn(id) != on
                        : id.equals(target))
                .toList();
        if (ids.isEmpty()) {
            ModViews.showChat(target.equals("all") ? "Every mod is " + (on ? "on" : "off") + " already."
                    : "No installed mod has the id " + target + ".", !target.equals("all"));
        } else if (on) {
            engine.turnOn(ids);
        } else {
            engine.turnOff(ids);
        }
        return 1;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static int runAdd(Path jar, boolean reopen) {
        HotwireEngine engine = HotwireClient.getEngine();
        if (!Files.isRegularFile(jar)) {
            ModViews.showChat("There is no file at " + jar + ".", true);
            return 1;
        }
        engine.dryRunAdd(jar).thenAccept(preview -> Minecraft.getInstance().execute(() -> {
            if (preview.mode() == AddMode.REFUSED) {
                ModViews.showChat(preview.name() + " can't be added: " + String.join(" ", preview.lines()), true);
            } else if (preview.mode() == AddMode.SAVE_AND_REOPEN && !reopen) {
                ModViews.showChat(preview.name() + " goes in with the world saved and opened again around it;"
                        + " /hotwire saveadd " + jar + " does that.", false);
            } else {
                engine.add(jar, preview.mode());
            }
        }));
        return 1;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static int runUpdate(Path jar) {
        HotwireEngine engine = HotwireClient.getEngine();
        engine.dryRunUpdate(jar).thenAccept(preview -> Minecraft.getInstance().execute(() -> {
            switch (preview) {
                case UpdatePreview.Ready ready -> engine.update(jar, ready.method());
                case UpdatePreview.SameVersion same -> ModViews.showChat(same.name() + " " + same.version()
                        + " is the version installed.", true);
                case UpdatePreview.Unreadable unreadable -> ModViews.showChat(unreadable.fileName()
                        + " is not a mod Hotwire can read.", true);
                case UpdatePreview.Refused refused -> ModViews.showChat(refused.reason(), true);
            }
        }));
        return 1;
    }

    private static Path getJar(CommandContext<FabricClientCommandSource> context) {
        return Path.of(StringArgumentType.getString(context, "jar").strip().replace("\"", ""));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> makeLiteral(String name) {
        //? if >=26.1 {
        return ClientCommands.literal(name);
        //?} else {
        /*return ClientCommandManager.literal(name);
        *///?}
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> makeArgument(String name,
            ArgumentType<T> type) {
        //? if >=26.1 {
        return ClientCommands.argument(name, type);
        //?} else {
        /*return ClientCommandManager.argument(name, type);
        *///?}
    }
}
