package dev.moon.hotwire.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class DevTools {
    private static final String MOD = "The mod's id or name; the first mod in development when left out";

    private DevTools() {
    }

    public static JsonArray list() {
        JsonArray tools = new JsonArray();
        tools.add(makeTool("status", "Every mod in development: its state, the build the game runs, how many builds are"
                + " kept and how many crashes were caught, and whether new builds are taken in."));
        tools.add(makeTool("builds", "The mod's builds as a tree: each one's number, name, the build it was made on,"
                + " whether it runs, is pinned, was merged or threw.", "mod", "string", MOD));
        tools.add(makeTool("build_report", "What one build changed: each class with the methods changed, added and"
                + " removed and their lines, each changed method's source lines lost (-) and gained (+) where both"
                + " could be read, new and removed classes, other files, notes, the git branch and commit it was built"
                + " on, and what it threw.",
                "mod", "string", MOD, "build", "integer", "The build's number"));
        tools.add(markRequired(makeTool("wait_for_build",
                "Waits for the mod's next build to go into the running game (or"
                + " fail, or need a restart), then answers what happened, the build's report and any crash its code"
                + " caused in the first seconds. Call it right after building: a build that went in during the last"
                + " ten seconds, and that no call was told of yet, is answered at once.", "mod", "string", MOD,
                "timeout_seconds", "integer", "The longest wait, 1 to 600; 120 when left out")));
        tools.add(markRequired(makeTool("roll_back", "Puts a kept build back into the running game and answers how it"
                + " went. Newer builds stay kept.", "mod", "string", MOD, "build", "integer",
                "The kept build to go back to"), "build"));
        tools.add(markRequired(makeTool("ab", "An A/B of two builds measured as the game runs: start holds the"
                + " running build against another, switch puts the other one in, stop goes back to the code's build;"
                + " each answers with each side's frames a second, server tick and memory made a second.", "mod",
                "string", MOD, "action", "string", "start, switch, stop or status", "build", "integer",
                "For start: the kept build to hold against the running one"), "action"));
        tools.add(markRequired(makeTool("restore", "Puts a kept build back into the running game and its sources"
                + " back into the project, so the IDE shows that code and the next build goes on from it. The files as"
                + " they were are kept; undo_restore puts them back.", "mod", "string", MOD, "build", "integer",
                "The kept build to restore"), "build"));
        tools.add(markRequired(makeTool("code", "A changed method's whole code as a build has it, or with before"
                + " true as the build it was made on had it, from the sources every build keeps.", "mod", "string",
                MOD, "build", "integer", "The build", "class", "string",
                "The class's binary name (dev.sample.Messages)",
                "method", "string", "The method's name, as build_report lists it", "before", "boolean",
                "The code before this build's change"), "build", "class", "method"));
        tools.add(makeTool("undo_restore", "Puts the project's files back as they were before the last restore.",
                "mod", "string", MOD));
        tools.add(markRequired(makeTool("merge_preview",
                "What merging these builds into one would make: the files and the"
                + " build each comes from, conflicts, builds left out, and which builds are thrown away. Changes"
                + " nothing.", "mod", "string", MOD, "builds", "array", "Build numbers to merge"), "builds"));
        tools.add(markRequired(makeTool("merge", "Merges builds into one, as the developer window's Merge. Nothing is"
                + " merged without confirm true; with conflicts, also anyway true. Show the user merge_preview"
                + " first: builds it throws away are gone.", "mod", "string", MOD, "builds", "array",
                "Build numbers to merge", "confirm", "boolean", "True once the user agreed to what is thrown away",
                "anyway", "boolean", "True to keep the newer version where builds conflict"), "builds", "confirm"));
        tools.add(markRequired(makeTool("save_jar", "Saves a kept build as a jar of its own under the game's"
                + " hotwire/exported folder, to hand to a tester. Before Minecraft 26.1 a build is named as the"
                + " development setup names the game, so it runs in a development setup only.", "mod", "string", MOD,
                "build", "integer", "The build"), "build"));
        tools.add(markRequired(makeTool("pin",
                "Pins or unpins a build: a pinned build is never dropped and stays through"
                + " merges.", "mod", "string", MOD, "build", "integer", "The build", "pinned", "boolean",
                "False to unpin; true when left out"), "build"));
        tools.add(markRequired(makeTool("rename", "Names a build; an empty name takes the name away.", "mod", "string",
                MOD, "build", "integer", "The build", "name", "string", "The name"), "build", "name"));
        tools.add(markRequired(makeTool("copy_build",
                "Copies a build as a build of its own beside it.", "mod", "string",
                MOD, "build", "integer", "The build to copy"), "build"));
        tools.add(makeTool("crashes", "The throws of the mod the game kept going past: what, where, how often, and the"
                + " class and line in the mod's source.", "mod", "string", MOD));
        tools.add(makeTool("history", "What happened to the mod, newest first: builds that went in, failed or need a"
                + " restart, crashes, merges.", "mod", "string", MOD, "limit", "integer", "How many, 1 to 100"));
        tools.add(makeTool("mixins", "The game classes the mod's mixins change, with other mods that change them too.",
                "mod", "string", MOD));
        tools.add(makeTool("reload_now", "Takes the mod's newest build in now, also while watching is paused.", "mod",
                "string", MOD));
        tools.add(makeTool("restart_mod", "Turns the mod off and on again, so its setup runs as at a start.", "mod",
                "string", MOD));
        tools.add(markRequired(makeTool("set_watching",
                "Pauses or resumes taking new builds in as they are made.", "on",
                "boolean", "False to pause"), "on"));
        tools.add(makeTool("game_log", "The end of the game's log.", "lines", "integer", "How many lines, 1 to 500;"
                + " 80 when left out", "contains", "string", "Only lines with this text"));
        return tools;
    }

    private static JsonObject makeTool(String name, String description, String... inputs) {
        JsonObject properties = new JsonObject();
        for (int at = 0; at + 2 < inputs.length; at += 3) {
            JsonObject input = new JsonObject();
            input.addProperty("type", inputs[at + 1]);
            if ("array".equals(inputs[at + 1])) {
                JsonObject items = new JsonObject();
                items.addProperty("type", "integer");
                input.add("items", items);
            }
            input.addProperty("description", inputs[at + 2]);
            properties.add(inputs[at], input);
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("description", description);
        out.add("inputSchema", schema);
        return out;
    }

    private static JsonObject markRequired(JsonObject tool, String... names) {
        JsonArray needed = new JsonArray();
        for (String name : names) {
            needed.add(name);
        }
        tool.getAsJsonObject("inputSchema").add("required", needed);
        return tool;
    }
}
