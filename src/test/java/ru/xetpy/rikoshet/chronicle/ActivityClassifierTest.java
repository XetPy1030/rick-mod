package ru.xetpy.rikoshet.chronicle;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityClassifierTest {
	private static final ActivityClassifier.Window FULL = new ActivityClassifier.Window(300, 20, 0, 0, 0);

	@Test
	void miningUnderground() {
		var w = new ActivityClassifier.Window(300, 20, 0, 15, 0);
		assertEquals(Activity.MINING, ActivityClassifier.classify(Map.of(Keys.MINED, 180L, Keys.ORES, 6L, Keys.DISTANCE, 200L), w));
	}

	@Test
	void buildingBeatsWalkingAround() {
		assertEquals(Activity.BUILDING, ActivityClassifier.classify(Map.of(Keys.PLACED, 250L, Keys.DISTANCE, 600L), FULL));
	}

	@Test
	void lumberIsNotMining() {
		assertEquals(Activity.LUMBER, ActivityClassifier.classify(Map.of(Keys.MINED, 60L, Keys.LOGS, 55L), FULL));
	}

	@Test
	void farmingCountsReplantAsFarming() {
		assertEquals(Activity.FARMING, ActivityClassifier.classify(Map.of(Keys.MINED, 120L, Keys.CROPS, 120L, Keys.PLANTED, 100L), FULL));
	}

	@Test
	void travelByElytra() {
		assertEquals(Activity.EXPLORING, ActivityClassifier.classify(Map.of(Keys.DISTANCE, 9000L, Keys.AVIATE, 8500L), FULL));
	}

	@Test
	void idleWindowIsAfkEvenWithFish() {
		var w = new ActivityClassifier.Window(300, 20, 18, 0, 0);
		assertEquals(Activity.AFK, ActivityClassifier.classify(Map.of(Keys.FISH, 12L), w));
	}

	@Test
	void littleActivityIsOther() {
		assertEquals(Activity.OTHER, ActivityClassifier.classify(Map.of(Keys.PLACED, 5L, Keys.DISTANCE, 80L), FULL));
	}

	@Test
	void shortWindowHasLowerThreshold() {
		var w = new ActivityClassifier.Window(60, 4, 0, 0, 0);
		assertEquals(Activity.BUILDING, ActivityClassifier.classify(Map.of(Keys.PLACED, 8L), w));
		assertEquals(Activity.OTHER, ActivityClassifier.classify(Map.of(Keys.PLACED, 8L), FULL));
	}
}
