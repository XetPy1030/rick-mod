package ru.xetpy.rikoshet.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
	@Test
	void defaultFileMatchesDefaults() throws IOException {
		List<String> warnings = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		ConfigLoader.Result r = ConfigLoader.parse(ConfigLoader.defaultText(), warnings, errors);
		assertEquals(List.of(), errors);
		assertEquals(List.of(), warnings);
		assertEquals(RikoshetConfig.defaults(), r.config());
	}

	@Test
	void routeOverrideKeepsOtherRoutes() {
		List<String> w = new ArrayList<>();
		List<String> e = new ArrayList<>();
		RikoshetConfig c = RikoshetConfig.read(Json5.parse("""
				{ ai: { routes: { flavor: { models: ["x-ai/grok-4.7@minimal"], timeout_seconds: 7 } } } }
				""").getAsJsonObject(), w, e);
		assertEquals(List.of(), e);
		assertEquals("x-ai/grok-4.7", c.ai().route("flavor").models().getFirst().id());
		assertEquals("minimal", c.ai().route("flavor").models().getFirst().effort());
		assertEquals(7, c.ai().route("flavor").timeoutSeconds());
		assertEquals("none", c.ai().route("flavor").reasoning(), "reasoning маршрута по умолчанию сохраняется");
		assertEquals(RikoshetConfig.DEFAULT_ROUTES.get("dialogue"), c.ai().route("dialogue"));
	}

	@Test
	void errorsAndWarnings() {
		List<String> w = new ArrayList<>();
		List<String> e = new ArrayList<>();
		RikoshetConfig.read(Json5.parse("""
				{ ai: { base_url: "http://evil", daily_budget_usd: -1, routes: { flavor: { models: ["bad model"] } } },
				  performance: { mspt_soft: 50, mspt_hard: 40 },
				  timezone: "Mars/Olympus",
				  typo_key: 1 }
				""").getAsJsonObject(), w, e);
		assertTrue(e.stream().anyMatch(s -> s.contains("base_url")), e.toString());
		assertTrue(e.stream().anyMatch(s -> s.contains("daily_budget_usd")), e.toString());
		assertTrue(e.stream().anyMatch(s -> s.contains("bad model")), e.toString());
		assertTrue(e.stream().anyMatch(s -> s.contains("mspt_hard")), e.toString());
		assertTrue(e.stream().anyMatch(s -> s.contains("timezone")), e.toString());
		assertTrue(w.stream().anyMatch(s -> s.contains("typo_key")), w.toString());
	}

	@Test
	void brokenSyntaxGivesNoConfig() {
		ConfigLoader.Result r = ConfigLoader.parse("{ ai: ", new ArrayList<>(), new ArrayList<>());
		assertNull(r.config());
		assertFalse(r.ok());
	}

	@Test
	void unknownFeatureIsWarning() {
		List<String> w = new ArrayList<>();
		List<String> e = new ArrayList<>();
		RikoshetConfig c = RikoshetConfig.read(Json5.parse("{ features: { rick: false, telepathy: true } }").getAsJsonObject(), w, e);
		assertFalse(c.feature("rick"));
		assertFalse(c.feature("telepathy"));
		assertTrue(w.stream().anyMatch(s -> s.contains("telepathy")), w.toString());
		assertEquals(List.of(), e);
		assertTrue(RikoshetConfig.read(new JsonObject(), w, e).feature("death_messages"));
	}
}
