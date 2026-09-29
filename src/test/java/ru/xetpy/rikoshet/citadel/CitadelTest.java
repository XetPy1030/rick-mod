package ru.xetpy.rikoshet.citadel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitadelTest {
	@Test
	void markerMetadata() {
		BlockPos p = new BlockPos(1, 2, 3);
		assertEquals(0f, Citadel.parse(p, "rick:south").yaw());
		assertEquals(180f, Citadel.parse(p, "spawn:north").yaw());
		assertEquals(90f, Citadel.parse(p, "x:west").yaw());
		assertEquals(-90f, Citadel.parse(p, "x:EAST").yaw());
		assertEquals(0f, Citadel.parse(p, "rick").yaw(), "без стороны — юг");
		assertNull(Citadel.parse(p, " "));
		assertEquals("portal_back", Citadel.name("Portal_Back:north"));
	}

	@Test
	void facingRoundTrip() {
		for (String f : new String[] {"south", "west", "north", "east"}) {
			assertEquals(f, Citadel.facing(Citadel.parse(BlockPos.ZERO, "a:" + f).yaw()));
		}
		assertEquals(180f, Citadel.snapYaw(170));
		assertEquals("east", Citadel.facing(-80));
	}

	@Test
	void portalGeometry() {
		Vec3 base = new Vec3(10.5, 64, 10.5);
		assertTrue(Portals.inside(new Vec3(10.9, 64, 10.2), base));
		assertFalse(Portals.inside(new Vec3(12, 64, 10.5), base), "в стороне");
		assertFalse(Portals.inside(new Vec3(10.5, 66, 10.5), base), "над кольцом");
		Vec3 south = Portals.forward(0);
		assertEquals(1, south.z, 1e-9);
		Vec3 north = Portals.forward(180);
		assertEquals(-1, north.z, 1e-9);
	}
}
