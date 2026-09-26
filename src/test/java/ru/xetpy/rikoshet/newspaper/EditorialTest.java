package ru.xetpy.rikoshet.newspaper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ru.xetpy.rikoshet.chronicle.analysis.DayReport;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorialTest {
	static final List<DayReport.Fact> FACTS = List.of(
			new DayReport.Fact("record", "u1", 60, "Токсик Рик (sh5dawg) — рекорд сервера за день: смертей 11"),
			new DayReport.Fact("nemesis", "u1", 20, "у Токсик Рик (sh5dawg) есть немезида — крипер"),
			new DayReport.Fact("gift", "u2", 20, "Морти (Benjurist), похоже, передал Джессика (Kate) алмаз ×10"),
			new DayReport.Fact("first", "u3", 40, "Джессика (Kate) первым на сервере побывал в измерении «Край»"));

	static DayReport report() {
		return new DayReport("2026-10-10", "суббота",
				new DayReport.ServerDay(3, 20_000, 2, "21:40", 14, 300, 5000, 2000, 30_000, "крипер", 5),
				List.of(), List.of(), FACTS,
				List.of(new DayReport.Forecast("online", "u1", 0.8, "Токсик Рик (sh5dawg) завтра зайдёт с вероятностью 80%")),
				List.of());
	}

	static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void briefMapsNumbersToVerbatimFacts() {
		Editorial.Brief b = Editorial.brief(json("""
				{"lead": 1, "stories": [
				  {"rubric": "Происшествия", "facts": [1, 2, 99], "angle": "рекорд и немезида — одна история"},
				  {"rubric": "Светская хроника", "facts": [3], "angle": "щедрость"},
				  {"rubric": "Пусто", "facts": [0, -5], "angle": "без фактов"},
				  {"rubric": "Повтор", "facts": [1], "angle": "уже было"}
				], "note": "не забыть прогноз"}"""), FACTS);
		assertNotNull(b);
		assertEquals(FACTS.getFirst().text(), b.lead());
		assertEquals(2, b.stories().size(), "заметки без годных фактов и с уже взятыми фактами выпали");
		assertEquals(List.of(FACTS.get(0).text(), FACTS.get(1).text()), b.stories().getFirst().facts());
		String pkg = Editorial.packageFrom(b, report(), new Editorial.Extras(List.of("сага о крипере — 3-й день"), List.of(), List.of("Старый заголовок")));
		assertTrue(pkg.startsWith("Выпуск за 2026-10-10, суббота."), pkg);
		assertTrue(pkg.contains("Главная тема: " + FACTS.getFirst().text()), pkg);
		assertTrue(pkg.contains("1. Происшествия — подача: рекорд и немезида"), pkg);
		assertTrue(pkg.contains("   - " + FACTS.get(2).text()), pkg);
		assertTrue(pkg.contains("сага о крипере"), pkg);
		assertTrue(pkg.contains("завтра зайдёт"), pkg);
		assertTrue(pkg.contains("Старый заголовок"), pkg);
		assertTrue(pkg.contains("Заметка выпускающего: не забыть прогноз"), pkg);
	}

	@Test
	void uselessBriefIsNull() {
		assertNull(Editorial.brief(json("{\"lead\": 1, \"stories\": [{\"rubric\": \"x\", \"facts\": [1], \"angle\": \"\"}], \"note\": \"\"}"), FACTS));
		assertNull(Editorial.brief(json("{\"lead\": 1, \"stories\": [], \"note\": \"\"}"), FACTS));
	}

	@Test
	void checkClipsAndDropsArticlesAboutHiddenPlayers() {
		String longBody = "очень ".repeat(100);
		Issue i = Editorial.check(json("""
				{"headline": "Заголовок, который слишком длинный для газеты и поэтому будет обрезан по концу фразы редакцией",
				 "articles": [
				   {"title": "Рекорд", "body": "Токсик Рик умер 11 раз."},
				   {"title": "Про скрытого", "body": "hidden_guy опять упал."},
				   {"title": "Длинная", "body": "%s"}
				 ],
				 "ad": "Плюмбус", "weather": "Дождь", "forecast": "Всё будет плохо"}""".formatted(longBody)),
				"2026-10-10", List.of(), Set.of("hidden_guy"), "m", 0.05);
		assertNotNull(i);
		assertTrue(i.headline().length() <= Editorial.HEADLINE);
		assertEquals(2, i.articles().size());
		assertTrue(i.articles().getLast().body().length() <= Editorial.BODY);
		assertEquals("ai", i.source());
		assertNull(Editorial.check(json("{\"headline\": \"x\", \"articles\": [{\"title\": \"a\", \"body\": \"b\"}], \"ad\": \"\", \"weather\": \"\", \"forecast\": \"\"}"),
				"d", List.of(), Set.of(), "m", 0), "одна заметка — не выпуск");
	}

	@Test
	void fallbackFromFacts() {
		Issue i = Editorial.fallback(report(), Map.of("ads", List.of("Плюмбус!"), "weather", List.of("Кислотный дождь")), new Random(1));
		assertEquals("fallback", i.source());
		assertEquals("Токсик Рик (sh5dawg) — рекорд сервера за день: смертей 11", i.headline());
		assertEquals("Немезида", i.articles().getFirst().title(), "главный факт — в заголовке, не в заметке");
		assertEquals("У Токсик Рик (sh5dawg) есть немезида — крипер.", i.articles().getFirst().body());
		assertEquals("Сводка за 10.10", i.articles().getLast().title());
		assertEquals("Плюмбус!", i.ad());
		assertEquals("Токсик Рик (sh5dawg) завтра зайдёт с вероятностью 80%", i.forecast());
	}
}
