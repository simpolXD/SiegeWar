package com.abyssaldominion.siegewar.core;

import java.util.*;

/** Main-thread-owned registry; item metadata alone never authorizes an ability. */
public final class IssuedRegistry {
    public record Binding(UUID owner, UUID generation, UUID life, String kit, String ability,
                          String action, String material) {
        public Binding {
            Objects.requireNonNull(owner); Objects.requireNonNull(generation);
            Objects.requireNonNull(life); Objects.requireNonNull(kit);
            Objects.requireNonNull(ability); Objects.requireNonNull(action); Objects.requireNonNull(material);
        }
    }
    private final Map<UUID, Binding> items = new HashMap<>();
    public UUID issue(Binding binding) {
        if (items.values().stream().filter(b -> b.owner().equals(binding.owner())).count() >= 128)
            throw new IllegalStateException("Issued item budget exceeded");
        UUID id = UUID.randomUUID();
        items.put(id, binding);
        return id;
    }
    public boolean accepts(UUID id, Binding binding) { return binding.equals(items.get(id)); }
    public void revoke(UUID owner) { items.values().removeIf(b -> b.owner().equals(owner)); }
    public int size() { return items.size(); }
}
