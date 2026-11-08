package de.muffin.universalprotect.api;

/** Position in einer Welt (Dimension-ID wie "minecraft:overworld"). */
public record Loc(String world, int x, int y, int z) {}
