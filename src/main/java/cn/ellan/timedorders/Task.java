package cn.ellan.timedorders;

import org.bukkit.Material;

record Task(
        String id,
        String name,
        Kind kind,
        Station station,
        String itemId,
        int quantity,
        int reward,
        int durationSeconds,
        double minimumScore,
        Material icon
) {
    enum Kind {
        CRAFTENGINE,
        BREW
    }

    enum Station {
        COOKED("维多的熟食店"),
        TAVERN("西码头酒馆"),
        GREENHOUSE("副岛温室"),
        CASINO_BAR("赌场酒吧"),
        FURNITURE("班鲁的家居店");

        private final String displayName;

        Station(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    String qualityText() {
        if (kind != Kind.BREW) {
            return "";
        }
        if (minimumScore >= 0.8D) {
            return "，至少四星品质";
        }
        if (minimumScore >= 0.6D) {
            return "，至少三星品质";
        }
        return "，不限品质";
    }
}
