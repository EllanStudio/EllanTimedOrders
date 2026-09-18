package cn.ellan.timedorders;

import java.util.UUID;

final class OrderState {
    private OrderState() {
    }

    record Active(String nonce, String taskId, long publishedAt, long offerExpiresAt) {
        String encode() {
            return String.join("|", nonce, taskId, Long.toString(publishedAt), Long.toString(offerExpiresAt));
        }

        static Active parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4) {
                return null;
            }
            try {
                return new Active(parts[0], parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    record Claim(String nonce, UUID playerId, String playerName, long claimedAt, long deadline) {
        String encode() {
            return String.join("|", nonce, playerId.toString(), playerName,
                    Long.toString(claimedAt), Long.toString(deadline));
        }

        static Claim parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length != 5) {
                return null;
            }
            try {
                return new Claim(parts[0], UUID.fromString(parts[1]), parts[2],
                        Long.parseLong(parts[3]), Long.parseLong(parts[4]));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
