# Hotwire for mod developers

Run your game from your mod's dev setup with Hotwire in it and every build you make goes into the running game. You
don't relaunch, you don't copy jars anywhere, you build and it's in.

## Setting it up

Put the Hotwire jar in your run folder's mods folder (`run/mods` with Loom) and start the game like you always do,
with `runClient` or your IDE's run button. Hotwire sees which mod runs from your build folders and watches them.

Then build the way you normally would, `gradlew classes` or the IDE's Build, and a popup tells you when it's in. That
usually takes a second or two after the build finishes.

## What goes in

On any Java, changes inside methods go in, and so do new fields, methods and classes, mixins included, along with
textures, models and language files. A few changes need the class rebuilt from scratch, like a new enum value or a new
superclass, and those wait for a restart. The popup says so when it happens, and it also tells you when

- your `onInitialize` changed, since it already ran at launch and what it registers comes in at the next start
- a class gained annotated fields (config and save libraries read those by reflection, and on plain Java they see
  them after a restart)

On the JetBrains Runtime everything goes in, YACL configs pick up their new options too. With Loom that's

```kotlin
tasks.named<JavaExec>("runClient") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")
}
```

and if you run from IntelliJ you can pick its bundled runtime and add the flag to your VM options.

## When your mod crashes

If your mod throws while the game runs, the game keeps going. You get a popup with what threw and where, as your own
file and line (a mixin's too, not the class it went into), and the full trace in your log. The popup stays up and
counts while your mod keeps throwing. Fix it, build, and it goes in.

Click a crash in the developer window and IntelliJ opens that line. With no IDE open, the place is copied instead.

## The developer window

Press F8 in game. It shows where your mod stands, what each build did, the crashes it caught and how often, where your
mixins meet other mods', and has buttons to reload, restart your mod (turned off and on again, so its setup runs like
at a start) or pause reloads while you're in the middle of something.

It handles like a window on Windows. Drag it by the title, size it from any edge or corner, double-click the title to
maximize it, or drag it to the left, right or top edge of the screen to snap it there. It opens where you left it, even
after a restart.

The − and + in its title set its own size, apart from the game's GUI scale.

Each build's report shows the lines you changed in every method, red for what went and green for what came, with the branch and commit it was built on and a tag when it had changes you hadn't committed yet. Save as jar puts any build in `hotwire/exported` to hand to a tester (from 26.1, before that a build only runs in a development setup).

When your mod crashes the card tells you which build last changed that line.

## Keys

Bind them in Controls, they start unbound

- Take the newest build now
- Roll back one build
- Swap with the last build, press it again to swap back, handy for comparing two builds

## Your builds after a restart

Turn on Keep builds after a restart and your builds, their names and pins are still there the next time you start the game. If you changed code while the game was closed, the start is a new build made on the one you left.

Before a build goes back in Hotwire checks its jar is still exactly what it saved, so a file that changed on disk never goes in half right, and it stops writing builds when your disk is nearly full.

## Build name on screen

Turn it on and the build each mod runs sits in a corner over the game, with the name you gave it. Open the developer window to drag it wherever you like.

## Leaked worlds

When you leave a world Hotwire checks whether one of your mod's statics still holds it, and if one does it tells you
which, like `MyMod.lastWorld`. A world that's held stays in memory with everything in it, so clear it when the
world closes.

## Coding agents

Theres a Hotwire MCP mod in our GitHub releases, put it in your run's mods folder next to Hotwire (or add it as `modRuntimeOnly`) and start the game like you always do. It writes a `.mcp.json` into your project, keeps it out of git, and a coding agent that reads `.mcp.json` picks it up the next time you open the project there, say yes once and that's the setup.

Then just ask your agent for a change. It builds, calls `wait_for_build` and gets back what went in, if it needs a restart and any crash with the file and line, so it can fix its own mistakes while your game keeps running. It can also see your builds and their reports, roll back, merge, pin, read your crashes, mixins and history, reload or restart your mod and read the game log, everything the developer window does.

It only listens on your own machine and needs the token in `config/hotwire-mcp.json`. The game log has the server's address if you'd rather add it yourself.

## Settings

All of this is under Settings, Developer, and each part can be turned off

- Reload when you build
- Catch your mod's crashes
- Watch for leaked worlds
- Reload popups (every reload, or only the ones with a problem)
- Keep builds after a restart
- Build name on screen
- Rebuild every switch

The Developer section only works when you run the game from a dev setup, so players never see it do anything.
