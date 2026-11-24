package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface Protection {
    boolean regionExists(Arena a);

    boolean pvp(Player actor, Location at);

    boolean build(Player actor, Location at);

    default boolean explosion(Player actor, Location at) {
        return true;
    }

    default void create(Arena a) {}
    default void begin(Arena a) {}
    default void end(Arena a) {}
    default void recover() {}

    static Protection absent() {
        return new Protection() {
            public boolean regionExists(Arena a) {
                return false;
            }

            public boolean pvp(Player p, Location l) {
                return true;
            }

            public boolean build(Player p, Location l) {
                return true;
            }
        };
    }
}
