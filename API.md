# Hotwire API

Other mods can switch mods through Hotwire. You get the same switch the player gets from Hotwire's screen so it follows the same rules and shows the same messages.

## Setting it up

```groovy
repositories {
    maven { url "https://api.modrinth.com/maven" }
}
dependencies {
    modCompileOnly "maven.modrinth:irenehotwire:VERSION"   // compile against it and never bundle it
}
```

Hotwire stays optional so check for it first, and keep everything that names Hotwire's classes in one class of its own so your mod still loads without it:

```java
final class HotwireCompat {
    static void turnOff(String modId) {
        HotwireApi.getInstance().turnOff(List.of(modId));
    }
}

if (FabricLoader.getInstance().isModLoaded("hotwire")) {
    HotwireCompat.turnOff("zoomify");
}
```

Theres a tiny example mod in [examples/api-example](examples/api-example) that toggles a mod with a command, copy from it.

Call it from any thread you want. Every future completes on the game thread so you can touch your screen in `thenAccept`.

**NEVER call `.join()` on a future from the game thread, that freezes the game.**

Switches, plans and update checks never fail their future, if something went wrong it's in what you get back. The store's lookups and `writeReport` do fail if Modrinth or the disk did.

## The prompt

Before your mod lets players switch anything you must show Hotwire's opening prompt. If you don't then Hotwire will scream in chat and name your mod.

```java
api.showPrompt();   // shows Hotwire's prompt over your screen
```

If you'd rather draw it in your own theme then take it as text:

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

How it works:

- Call one of the two before `turnOff`, `turnOn` or `planChange`, once per game session is enough
- Both count as showing it, even when the player accepted it long ago and nothing shows
- The player only sees it again when Hotwire gets a big update
- Reading stuff (`getMods`, `getStatus`, the store's pages) doesn't need it
- Adding a mod from Modrinth is refused until the player accepted it

## Switching

```java
SwitchPreview preview = api.previewOff(List.of("zoomify"));   // what would go with it, nothing changes yet
api.turnOff(List.of("zoomify")).thenAccept(result -> {
    if (result.isDone()) {
        // result.switched() has every mod that went off, zoomify and whatever went with it
    }
});
api.turnOn(List.of("zoomify"));
```

A switch ends one of four ways:

- `DONE`, every mod you asked for is off or on and so is everything that went with it
- `REFUSED`, nothing changed because a mod can't switch or Hotwire isn't ready yet
- `NEEDS_REOPEN`, nothing changed because the open world is in the way. Plan the same switch with `planChange` and the player can accept it with the world saved and opened again around it
- `FAILED`, it went wrong and the game log says why

`result.message()` is what Hotwire told the player so show it if you want. `result.reason()` is the same thing for your code, like `NOT_READY` or `WORLD_LOCKED`, and previews and plans have one too.

## What you can't switch

Your own mod, any mod it needs and any mod whose code is part of the call. Hotwire tells which mod is asking from the stack so there is no way around this. Hotwire itself, Fabric API, Sodium, Lithium, Phosphor, FerriteCore, Ixeris, C2ME, ModernFix and VulkanMod never switch either.

`api.getMods()` gives you every installed mod as Hotwire's list shows it, seen from your mod. `switchable` says whether you may switch it and `blocked` says why not in the player's words so you can show it in your own list. You can read it every frame.

## Changing many mods at once

Turning mods off and on, updating them and adding them all go through one call. Everything you ask for is judged together so two mods that only work at each other's new versions can update as a pair. If you ask for one of a pair then the plan pulls the other in when it has to and says so in its lines.

```java
api.planChange(new ChangeRequest()
        .off("zoomify")
        .on("sodium-extra")
        .update("sodium", "lithium")   // to the newest on Modrinth
        .add("iris")                   // from Modrinth by id or slug, add("iris", versionId) for one version
        .jar(pickedJar)                // a jar the player picked, an update if the mod is installed and an add if not
        .tag("my-mod")                 // optional, see below
).thenAccept(plan -> {
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

Nothing changes until the plan is accepted but updates and adds get downloaded for it. Adding a mod is always the player's call so show them the lines and let them press.

The reasons are:

- `NONE`, nothing stands in the way
- `NOT_READY`, Hotwire is starting or can't change classes on this Java
- `NEEDS_MOD`, a mod is missing or too old and nothing you asked for brings it
- `NEEDS_RESTART`, some of it only happens at the next start
- `NEEDS_REOPEN`, the world gets saved and opened again around it
- `WORLD_LOCKED`, the world is locked against switching
- `INSTALLED`, the mod you're adding is already there
- `REFUSED`, a mod can't change or couldn't be fetched and the lines say why
- `FAILED`, it went wrong and the game log says why

What a change did is on its result. `appliedNow()` has every mod it changed in the running game and `atNextStart()` has the ones waiting for the next start.

A mod that was off since an earlier launch (`offSinceStart` in `getMods()`) comes back from its jar when you turn it on.

If you give the request a tag then every download its plan starts carries it in `StoreDownload.tag()`, so you can pause or stop your own downloads and nobody else's.

## The store

You can put the Modrinth store in your own theme, everything Hotwire's store does you can do too. Anywhere it says `idOrSlug` you can pass a project's id or its slug.

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

To add a mod to the running game use `planChange` with `.add("zoomify")`. The store's other buttons:

```java
api.queueForNextStart("zoomify");            // into the mods folder for the next start
api.saveToDownloads(query, "zoomify");       // into .minecraft/hotwire/downloads
api.openDownloads();                         // opens that folder
api.fetchPack(StoreKind.SHADER, "complementary-reimagined").thenAccept(result -> {});
api.findPackState(StoreKind.SHADER, "complementary-reimagined");
api.togglePack(StoreKind.SHADER, "complementary-reimagined").thenAccept(result -> {});
api.getStoreDownloads();   // read it every frame
api.pauseDownload(download.id());
api.resumeDownload(download.id());
api.stopDownload(download.id());
```

The first three take a version's id as one more argument if you don't want the newest.

`getStoreDownloads` has every download going, that's mods and updates and packs and Hotwire's own update, oldest first. One that ended stays in the list for a moment so you can show how it went.

- `name()`, the file
- `fraction()`, 0 to 1 for your bar. `done()` and `total()` are the bytes and `total()` is 0 if Modrinth didn't say the size
- `state()`, one of `WAITING`, `GETTING`, `PAUSED`, `DONE`, `FAILED` or `STOPPED`
- `over()` and `ok()`, whether it ended and whether it worked
- `id()`, for the three calls above
- `tag()`, the tag of the request that started it

Pausing lets go of the connection and keeps what came, resuming goes on from there and stopping throws away what came. Stopping a mod's download stops the libraries it came with too since the mod is no use without them. All three return false once the download is over.

## Updates

```java
api.checkUpdates().thenAccept(found -> {
    found.updates();   // id, name, installed version, new version and Modrinth project id of each
    found.failure();   // why it couldn't ask Modrinth, empty when it could
});
```

If `failure()` isn't empty then you don't know if there are updates so don't tell the player there are none.

Hotwire itself:

```java
api.findNewerHotwire();   // the version, empty when there's none
api.updateHotwire().thenAccept(result -> {});   // fetched and queued for the next start
```

When an update can't go in place Hotwire runs the new version as a copy under an id of its own until the next start. `getMods()` lists the copy with `copyOf()` set to the mod it stands in for so keep its settings under that id.

## Waiting for the next start

```java
api.getPendingTasks();             // every row of Pending tasks, kind() says what each one is
api.cancelPendingTask("zoomify");  // false if it couldn't
api.isAddedThisSession("zoomify");
```

## Following along

```java
api.getStatus();        // starting, ready, busy or unavailable, with progress and why, read it every frame
api.getProgress();      // for a progress bar, read it every frame
api.awaitIdle();        // completes once nothing is switching, adding or updating
api.openScreen();       // open Hotwire duh
api.findIcon("zoomify");   // the icon file's bytes from the mod's jar, empty when it has none

Registration heard = api.addListener(result -> refreshMyList());   // fires after any switch, yours or the player's
heard.remove();                                                     // when you don't want it anymore
```

`UNAVAILABLE` means this Java can't change loaded classes so Hotwire won't be ready this session.

A lifecycle listener hears every change from every path, even the player's own clicks on Hotwire's screen:

```java
api.addLifecycleListener(new ModLifecycleListener() {
    public void prepareOff(List<String> ids) { }     // still on and safe to call, save their settings here
    public void finishOn(List<String> ids) { }       // back on with the objects they had
    public void prepareStart(List<String> ids) { }   // added mods, live copies, mods off since launch, and new entrypoints of an update
    public void finishStart(List<String> ids, boolean ok) { }
    public void finishUpdate(List<String> ids) { }   // new code in place under the same ids
});
api.addLifecycleListener("zoomify", listener);       // only the changes zoomify is part of
```

Every call runs on the game thread and Hotwire waits for it, up to 5 seconds. `ids` is the whole change with the mods going along. A switch that fails after `prepareOff` answers with `finishOn` for the mods that stayed on so you never get stuck with half your pages gone. While your mod is off its listeners aren't called.

`getRevision()` goes up when a call is added, so if you need a newer one ask `api.hasRevision(2)` first.

If something goes wrong on your side `api.writeReport("why")` writes a Hotwire report zip and gives you its path.
