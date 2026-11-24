package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.core.IssuedRegistry;
import com.abyssaldominion.siegewar.core.IssuedRegistry.Binding;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IssuedRegistryTest {
    @Test void forgedCopiedAndExpiredBindingsCannotAuthorizeGear() {
        var registry = new IssuedRegistry();
        UUID owner = UUID.randomUUID(), generation = UUID.randomUUID(), life = UUID.randomUUID();
        var binding = new Binding(owner, generation, life, "wizard", "attack_wand", "", "BLAZE_ROD");
        UUID item = registry.issue(binding);
        assertTrue(registry.accepts(item, binding));
        assertFalse(registry.accepts(UUID.randomUUID(), binding));
        assertFalse(registry.accepts(item, new Binding(UUID.randomUUID(), generation, life, "wizard", "attack_wand", "", "BLAZE_ROD")));
        assertFalse(registry.accepts(item, new Binding(owner, UUID.randomUUID(), life, "wizard", "attack_wand", "", "BLAZE_ROD")));
        assertFalse(registry.accepts(item, new Binding(owner, generation, UUID.randomUUID(), "wizard", "attack_wand", "", "BLAZE_ROD")));
        assertFalse(registry.accepts(item, new Binding(owner, generation, life, "wizard", "support_wand", "", "BLAZE_ROD")));
        assertFalse(registry.accepts(item, new Binding(owner, generation, life, "wizard", "attack_wand", "", "STICK")));
        registry.revoke(owner);
        assertFalse(registry.accepts(item, binding));
    }
    @Test void revocationAndBudgetArePerOwner() {
        var registry = new IssuedRegistry();
        UUID owner = UUID.randomUUID();
        var a = new Binding(owner, UUID.randomUUID(), UUID.randomUUID(), "knight", "", "", "IRON_SWORD");
        var b = new Binding(UUID.randomUUID(), a.generation(), a.life(), "knight", "", "", "IRON_SWORD");
        UUID other = registry.issue(b);
        for (int i = 0; i < 128; i++) registry.issue(a);
        assertThrows(IllegalStateException.class, () -> registry.issue(a));
        registry.revoke(owner);
        assertEquals(1, registry.size());
        assertTrue(registry.accepts(other, b));
        assertNotNull(registry.issue(a));
    }
}
