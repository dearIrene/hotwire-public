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

## The store

You can put the Modrinth store in your own theme, everything Hotwire's store does you can do too.

```java
StoreQuery query = StoreQuery.of("zoom");          // empty for the most downloaded
api.searchStore(query).thenAccept(page -> {});     // 20 at a time, says if the player has each and if it has an update
api.searchStore(query.withOffset(20));             // the next page
api.searchStore(query.withKind(StoreKind.SHADER)); // mods, resource packs, shaders or data packs
api.searchStore(query.withCategory("optimization"));
api.getStoreCategories(StoreKind.MOD);
api.findStoreProject(query, "zoomify").thenAccept(project -> {});
```

You can browse other versions and loaders (fabric, quilt, forge or neoforge) with `query.withBrowsing("1.20.1", "forge")` and `findStoreGameVersions()`, those can only be saved though.

Adding a mod is always the player's call, so ask for it and draw what you get back:

```java
api.requestAdd("zoomify", "").thenAccept(add -> {   // "" for the newest, or a version's id
    add.getTitle();
    add.getLines();       // show every line as it is
    add.needsReopen();
    add.canAdd();         // false when it can't be added at all, the lines say why
    add.accept();         // tells you how it went like a switch does
    add.cancel();
});
```

Some mods can only go in at the next start. The lines say so and `accept()` queues them. A mod that carries a newer version of a library the game runs updates that library as it goes in, and the lines say that too.

The store's other buttons:

```java
api.queueForNextStart("zoomify", "");
api.saveToDownloads(query, "zoomify", "");   // into .minecraft/hotwire/downloads
api.openDownloads();                          // opens that folder
api.fetchPack(StoreKind.SHADER, "complementary-reimagined", "");
api.findPackState(StoreKind.SHADER, id);
api.togglePack(StoreKind.SHADER, id);
api.getStoreDownloads();   // read it every frame
api.pauseDownload(download.id());
api.resumeDownload(download.id());
api.stopDownload(download.id());
```

`getStoreDownloads` has every download going: mods, updates, packs and Hotwire's own update, oldest first. One that ended stays in the list for a moment so you can show how it went.

- `name()`, the file
- `fraction()`, 0 to 1 for your bar. `done()` and `total()` are the bytes, `done()` moves a percent at a time and `total()` is 0 if Modrinth didn't say the size
- `state()`, one of `WAITING`, `GETTING`, `PAUSED`, `DONE`, `FAILED` or `STOPPED`
- `over()` and `ok()`, whether it ended and whether it worked
- `id()`, for the three calls above

Pausing lets go of the connection and keeps what came, resuming goes on from there, and stopping throws away what came. Stopping a mod's download stops the libraries it came with too, the mod is no use without them. All three return false once the download is over. They need revision 7, so check `getRevision()` first.

To add a jar the player picked, use `planChange` below.

## Changing many mods at once

Turning mods off and on, updating them and adding them all go through one call. Everything you ask for is judged together, so two mods that only work at each other's new versions can update as a pair, and mods coming on are judged with the others already off. Ask for one of a pair and the plan pulls the other in when it has to, and says so in its lines. A mod kept off since an earlier launch (`offSinceStart` in `getMods()`) comes back from its jar when you turn it on.

```java
api.planChange(new ChangeRequest(
        List.of("zoomify"),            // turn off
        List.of("sodium-extra"),       // turn on
        List.of("sodium", "lithium"),  // update to the newest on Modrinth
        List.of("iris"),               // add from Modrinth, id or slug
        List.of(pickedJar)             // jars the player picked, an update if the mod is installed, else an add
)).thenAccept(plan -> {
    plan.getLines();               // show every line as it is
    plan.canApply();
    plan.getReason();              // why, so you can pick your buttons
    plan.needsReopen();
    plan.accept();                 // when the player presses, tells you how it went like a switch does
    plan.canWaitForNextStart();
    plan.acceptAtNextStart();      // adds and updates only
    plan.cancel();
});
```

Nothing changes until the plan is accepted, but updates and adds are downloaded for it. Always let the player decide.

The reasons are:

- `NONE`, nothing stands in the way
- `NOT_READY`, Hotwire is starting or can't change classes on this Java
- `NEEDS_MOD`, a mod is missing or too old and nothing you asked for brings it
- `NEEDS_RESTART`, some of it only happens at the next start
- `NEEDS_REOPEN`, the world gets saved and opened again around it
- `WORLD_LOCKED`, the world is locked against switching
- `REFUSED`, a mod can't change or couldn't be fetched, the lines say why

These futures don't fail. A plan that can't go says why in its lines, and the rest is in Hotwire's log.

What a change did is on its result (revision 9). `appliedNow()` has every mod it changed in the running game, the mods that went along and the adds and updates included, and `atNextStart()` has the ones waiting for the next start. A plan that only switches mods one way and needs the world reopened says so with `needsReopen()`, and `accept()` saves the world and opens it again around the switch, same as Hotwire's screen.

Give the request a tag as its last argument and every download its plan starts carries it in `StoreDownload.tag()`, so you can pause or stop your own and nobody else's. `requestAdd(idOrSlug, versionId, tag)` does the same for an add.

## Updates

```java
api.checkUpdates().thenAccept(found -> {
    found.updates();   // id, name, installed version, new version and Modrinth project id of each
    found.failure();   // why it couldn't ask Modrinth, empty when it could
});
```

If `failure()` isn't empty then you don't know if there are updates, so don't tell the player there are none.

Hotwire itself:

```java
api.findNewerHotwire();   // the version, empty when there's none
api.updateHotwire();      // fetched and queued for the next start
api.queueHotwireUpdate().thenAccept(result -> { });   // the same, and tells you when it's queued or why not (revision 9)
```

When an update can't go in place, Hotwire runs the new version as a copy under an id of its own until the next start. `getMods()` lists the copy with `copyOf()` set to the mod it stands in for, so keep its settings under that id. `copyOf()` is null for every other mod (revision 9).

## Waiting for the next start

```java
api.getPendingTasks();             // what Pending tasks lists
api.cancelPendingTask("zoomify");  // false if it couldn't
api.isAddedThisSession("zoomify");
```

## Icons

```java
api.findIcon("zoomify");   // the icon file's bytes from the mod's jar, empty when it has none
```

## Following along

```java
api.getStatus();        // starting, ready, busy or unavailable, with progress and why, read it every frame
api.awaitIdle();        // completes once nothing is switching, adding or updating
api.isSwitching();      // whether a switch is running, whoever asked for it
api.getProgress();      // for a progress bar, read it every frame
api.addListener(result -> refreshMyList());   // fires after any switch, yours or the player's
api.addLifecycleListener(listener);           // every off, on, start and update, see below (revision 9)
api.openScreen();       // opens Hotwire
```

A lifecycle listener hears every change from every path, the player's own clicks on Hotwire's screen included:

```java
api.addLifecycleListener(new ModLifecycleListener() {
    public void beforeOff(List<String> ids) { }     // still on and safe to call, save their settings here
    public void afterOn(List<String> ids) { }       // back on with the objects they had
    public void beforeStart(List<String> ids) { }   // added mods, live copies and mods off since launch
    public void afterStart(List<String> ids, boolean ok) { }
    public void afterUpdate(List<String> ids) { }   // new code in place under the same ids
});
```

Every call runs on the game thread and Hotwire waits for it, up to 5 seconds. `ids` is the whole change, the mods going along included. A switch that fails after `beforeOff` answers with `afterOn` for the mods that stayed on, so you never get stuck with half your pages taken out. A listener of a mod that's off isn't called.

`isReady()` is false while the game is starting, and stays false on a Java that can't change loaded classes. `getRevision()` goes up when a call is added, check it if you need a newer one.

If something goes wrong on your side `api.writeReport("why")` writes a Hotwire report zip and gives you its path.
