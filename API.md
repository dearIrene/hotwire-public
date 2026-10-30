# Hotwire API

Other mods can switch mods through Hotwire. You get the same switch the player gets from Hotwire's screen, same rules and same messages.

Hotwire stays optional, check for it first:

```java
if (FabricLoader.getInstance().isModLoaded("hotwire")) {
    HotwireApi api = HotwireApi.getInstance();
}
```

Add Hotwire as a compile dependency, never bundle it.

## The prompt

Before your mod lets players switch anything you must show Hotwire's opening prompt. If you don't then Hotwire will scream in chat and name your mod.

```java
api.showPrompt();   // shows Hotwire's prompt over your screen, once per player
```

If you'd rather draw it in your own theme, take it as text and draw it yourself:

```java
OpeningPrompt prompt = api.findOpeningPrompt();   // null once the player accepted it
prompt.getTitle();
prompt.getLines();            // show every line as it is
prompt.getWaitSeconds();      // how long our prompt keeps the accept button disabled, pick your own wait if you want
prompt.getAcceptText();
prompt.getOpenHotwireText();
prompt.accept();              // when the player presses accept
prompt.acceptAndOpenHotwire();
```

Taking the prompt counts as showing it, so a player who already accepted never sees it again.

## Switching

```java
SwitchPreview preview = api.previewOff(List.of("zoomify"));   // what would go with it, nothing changes yet
api.turnOff(List.of("zoomify")).thenAccept(result -> {
    if (result.kind() == SwitchResult.Kind.DONE) {
        // result.switched() has every mod that went off, zoomify and whatever went with it
    }
});
api.turnOn(List.of("zoomify"));
```

A switch ends one of four ways:

- `DONE`, every mod you asked for is off or on, and so is everything that went with it
- `REFUSED`, nothing changed, a mod can't switch or Hotwire isn't ready yet
- `NEEDS_REOPEN`, nothing changed, the open world is in the way. The player can do it from Hotwire's screen, which saves the world and opens it again around the switch
- `FAILED`, nothing changed and the game log says why

`result.message()` is what Hotwire told the player, show it if you want.

**NEVER call `.join()` on the future from the game thread, that freezes the game.** Use `thenAccept`.

## What you can't switch

Your own mod, any mod it needs and any mod whose code is part of the call. Hotwire tells which mod is asking from the stack so there is no way around this. On top of that Hotwire itself, Fabric API, Sodium, Lithium, Phosphor, FerriteCore, Ixeris, C2ME, ModernFix and VulkanMod never switch.

`api.getMods()` gives you every installed mod as Hotwire's list shows it, seen from your mod. `switchable` says whether you may switch it and `blocked` says why not in the player's words, so you can show it in your own list.

## Following along

```java
api.isSwitching();      // whether a switch is running, whoever asked for it
api.getProgress();      // for a progress bar, read it every frame
api.addListener(result -> refreshMyList());   // fires after any switch, yours or the player's
api.openScreen();       // opens Hotwire
```

`isReady()` is false while the game is starting, and stays false on a Java that can't change loaded classes. `getRevision()` goes up when a call is added, check it if you need a newer one.

If something goes wrong on your side `api.writeReport("why")` writes a Hotwire report zip and gives you its path.
