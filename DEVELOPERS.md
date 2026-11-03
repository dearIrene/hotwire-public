# Hotwire for mod developers

If you're working on a mod you can have the game pick up your new build while it's running so you don't relaunch
every time you change something.

This is revision 1 and it's temporary, it works on any Java but a class that changes shape still needs a restart.
Revision 2 is built on JBR (the JetBrains Runtime, the one IntelliJ runs your game on) where shape changes reload
too, so if you launch from the IDE you'll get the whole thing.

> Revision 2 is still being worked on

## Setting it up

Put Hotwire in the mods folder of the game you test in, open Hotwire in the game and turn on Developer under
Advanced. Then make your build put the jar into `hotwire/dev` in the game folder, with gradle that's

```groovy
tasks.register('devDrop', Copy) {
    from tasks.jar
    into "C:/path/to/.minecraft/hotwire/dev"
}
tasks.jar.finalizedBy 'devDrop'
```

and from then on `gradlew jar` reloads your mod in the game.

## What you'll see

The log tells you what happened

- `N classes took new code` means it worked and your mod is running the new code with everything it had still there
- `new entrypoints run` means you added an entrypoint and it got run, the ones from launch don't run again so nothing
  registers twice
- `class X changed shape` means the JVM won't take that one while the game runs (a new superclass, a new enum constant,
  a changed record or a mixin into a class that's already loaded) so that jar waits for the next start

Dropping the same jar again does nothing and a jar with the same version but different bytes still counts.

## Good to know

- Anything you read with `getResource` still comes from the first jar of the session, textures and models and
  language files are reloaded though so those are new
- The jar you dropped last goes into your mods folder when you close the game so the next start has it too, you
  can cancel that from Pending tasks
- If the log ever says `<your mod> is off, yet its code ran` that's a Hotwire bug so send the report
