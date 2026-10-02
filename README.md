# Anchor Macro (Fabric 1.21.1, loader 0.16.14)

Right-click a block with a sword -> places anchor, charges it with glowstone,
places a glowstone block between you and it, detonates with a totem, then
switches back to the sword.

Hotbar needs: Respawn Anchor, 2+ Glowstone, Totem of Undying (hotbar or offhand).
Works in the Overworld/End (anchors only explode there). J toggles the mod.
Sneak + right-click bypasses it.

## Build
Java 21 + Gradle 8.11+ (or open the folder in IntelliJ IDEA and let it import).

    gradle build

The jar appears in build/libs/anchormacro-1.0.0.jar - drop it in your mods
folder along with Fabric API (0.115.0+1.21.1 or newer for 1.21.1).

Use only where automation like this is allowed.
