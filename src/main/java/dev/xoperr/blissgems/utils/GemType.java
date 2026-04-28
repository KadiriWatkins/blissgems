package dev.xoperr.blissgems.utils;

public enum GemType {
    ASTRA("astra", "Astra", "Wield phantom daggers and explore astral dimensions"),
    AURATUS("auratus", "Auratus", "Wield divine chains and an unbreakable parry shield"),
    FIRE("fire", "Fire", "Burn your enemies with charged fireballs and cozy campfires"),
    FLUX("flux", "Flux", "Stun enemies and unleash electric power"),
    HERETIC("heretic", "Heretic", "Bleed your enemies and link their fates in blood"),
    LIFE("life", "Life", "Heal yourself and drain the life from enemies"),
    PUFF("puff", "Puff", "Defy gravity with double jumps and immunity to fall damage"),
    SPEED("speed", "Speed", "Move faster and sedate your foes"),
    STRENGTH("strength", "Strength", "Empower your strikes and hunt your enemies"),
    WEALTH("wealth", "Wealth", "Find fortune and fuel an empire");

    private final String id;
    private final String displayName;
    private final String description;

    private GemType(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String getId() { return this.id; }
    public String getDisplayName() { return this.displayName; }
    public String getDescription() { return this.description; }

    public String getColor() {
        return switch (this) {
            case ASTRA    -> "\u00a7d";
            case AURATUS  -> "\u00a7e";
            case FIRE     -> "\u00a7c";
            case FLUX     -> "\u00a7b";
            case HERETIC  -> "\u00a74";
            case LIFE     -> "\u00a7a";
            case PUFF     -> "\u00a7f";
            case SPEED    -> "\u00a7e";
            case STRENGTH -> "\u00a74";
            case WEALTH   -> "\u00a76";
        };
    }

    public static GemType fromOraxenId(String oraxenId) {
        if (oraxenId == null) return null;
        for (GemType type : GemType.values()) {
            if (!oraxenId.toLowerCase().startsWith(type.id + "_gem")) continue;
            return type;
        }
        return null;
    }

    public static int getTierFromOraxenId(String oraxenId) {
        if (oraxenId == null) return 1;
        if (oraxenId.endsWith("_gem_t2")) return 2;
        return 1;
    }

    public static String buildOraxenId(GemType type, int tier) {
        return type.getId() + "_gem_t" + tier;
    }

    public static boolean isGem(String oraxenId) {
        return oraxenId != null && oraxenId.contains("_gem_t");
    }
}