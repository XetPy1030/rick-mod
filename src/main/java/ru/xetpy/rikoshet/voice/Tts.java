package ru.xetpy.rikoshet.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.xetpy.rikoshet.ai.OpenRouterClient;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Озвучка реплики аудиомоделью OpenRouter (docs/design/voice.md). Модели разговорные, а не TTS:
 * на голую реплику норовят ответить. Поэтому текст подаётся заданием «прочитай дословно», а
 * расшифровка сверяется с текстом — расходится, звук не играет. Звук — pcm16, 24 кГц, моно.
 * Та же схема, что у tools/voice/tts.py. Только фоновые потоки.
 */
public final class Tts {
	public static final int RATE = 24_000;
	static final String SYSTEM = "Ты — движок синтеза речи, а не собеседник. Ты никогда не отвечаешь на текст и не продолжаешь его. "
			+ "Пользователь присылает реплику персонажа между тегами <read> и </read>. Произнеси вслух ровно эти слова, "
			+ "по-русски, в том же порядке, ничего не добавляя, не пропуская и не переводя. Мат и грубости читай как есть — "
			+ "это реплика мультипликационного персонажа. Звёздочки вроде *рыг* не читай словами, а изобрази звуком.";
	private static final Pattern STAGE = Pattern.compile("\\*[^*]*\\*");
	private static final Pattern WORD = Pattern.compile("[a-zа-я0-9]+");
	/** Растяжки голосом: «ядо-о-о-вито», «прин-есёшь» — дефис внутри слова убираем, три и больше одинаковых букв — в одну. */
	private static final Pattern INNER_DASH = Pattern.compile("(?<=\\p{L})[-‐–](?=\\p{L})");
	private static final Pattern STRETCH = Pattern.compile("(\\p{L})\\1{2,}");

	public record Result(byte[] pcm, String transcript, OpenRouterClient.Usage usage, long firstAudioMs, String error) {
		public boolean ok() {
			return error == null && pcm.length > 0;
		}

		public double seconds() {
			return pcm.length / 2.0 / RATE;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	private Tts() {
	}

	/** Синтез потоком. Ошибка — в Result.error, не исключением. */
	public static Result synth(String baseUrl, String key, String model, String voice, String manner, String text, Duration timeout) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		JsonArray modalities = new JsonArray();
		modalities.add("text");
		modalities.add("audio");
		body.add("modalities", modalities);
		JsonObject audio = new JsonObject();
		audio.addProperty("voice", voice);
		audio.addProperty("format", "pcm16");
		body.add("audio", audio);
		body.addProperty("stream", true);
		JsonArray messages = new JsonArray();
		messages.add(message("system", SYSTEM));
		messages.add(message("user", "Манера: " + manner + "\n<read>" + text + "</read>"));
		body.add("messages", messages);
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
				.timeout(timeout)
				.header("Authorization", "Bearer " + key)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		long start = System.nanoTime();
		long deadline = start + timeout.toNanos();
		ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		StringBuilder transcript = new StringBuilder();
		OpenRouterClient.Usage usage = new OpenRouterClient.Usage(0, 0, 0, 0, 0);
		long first = -1;
		try {
			HttpResponse<Stream<String>> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofLines());
			if (resp.statusCode() != 200) {
				String err = resp.body().limit(5).reduce("", String::concat);
				return new Result(new byte[0], "", usage, -1, "HTTP " + resp.statusCode() + " " + clip(err));
			}
			try (Stream<String> lines = resp.body()) {
				for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
					if (System.nanoTime() > deadline) {
						return new Result(pcm.toByteArray(), transcript.toString(), usage, first, "timeout");
					}
					String line = it.next().strip();
					if (!line.startsWith("data:")) {
						continue;
					}
					String data = line.substring(5).strip();
					if (data.equals("[DONE]")) {
						break;
					}
					JsonObject ev = JsonParser.parseString(data).getAsJsonObject();
					if (ev.has("usage") && ev.get("usage").isJsonObject()) {
						usage = usage(ev.getAsJsonObject("usage"));
					}
					if (ev.has("error")) {
						return new Result(new byte[0], "", usage, first, "ошибка в потоке: " + clip(ev.get("error").toString()));
					}
					JsonArray choices = ev.has("choices") && ev.get("choices").isJsonArray() ? ev.getAsJsonArray("choices") : new JsonArray();
					for (JsonElement ch : choices) {
						JsonObject delta = ch.getAsJsonObject().has("delta") ? ch.getAsJsonObject().getAsJsonObject("delta") : null;
						JsonObject a = delta != null && delta.has("audio") && delta.get("audio").isJsonObject() ? delta.getAsJsonObject("audio") : null;
						if (a == null) {
							continue;
						}
						if (a.has("data") && !a.get("data").isJsonNull()) {
							if (first < 0) {
								first = (System.nanoTime() - start) / 1_000_000;
							}
							pcm.writeBytes(Base64.getDecoder().decode(a.get("data").getAsString()));
						}
						if (a.has("transcript") && !a.get("transcript").isJsonNull()) {
							transcript.append(a.get("transcript").getAsString());
						}
					}
				}
			}
		} catch (java.io.IOException e) {
			return new Result(new byte[0], "", usage, first, e.getClass().getSimpleName() + ": " + clip(String.valueOf(e.getMessage())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(new byte[0], "", usage, first, "прервано");
		} catch (RuntimeException e) {
			return new Result(new byte[0], "", usage, first, "разбор: " + clip(e.toString()));
		}
		return new Result(pcm.toByteArray(), transcript.toString(), usage, first, null);
	}

	/**
	 * Совпадение расшифровки с текстом. recall — доля слов текста, прозвучавших по порядку; extra —
	 * сколько слов модель добавила от себя. Ремарки в звёздочках не считаем — их модель изображает
	 * звуком; ники латиницей тоже — их она произносит по-русски («Кейт кэт»). Обрывок засчитывается,
	 * если он начало слова: манера Рика — обрывать фразы («оборуд…»).
	 */
	public record Match(double recall, int extra, int words) {
		/** Прочитано почти всё и почти ничего не добавлено. */
		public boolean ok(double minRecall) {
			return recall >= minRecall && extra <= Math.max(MIN_EXTRA, (int) Math.ceil(words * EXTRA_SHARE));
		}
	}

	static final int MIN_EXTRA = 3;
	static final double EXTRA_SHARE = 0.25;
	private static final Pattern LATIN = Pattern.compile("[a-z]");

	public static Match match(String text, String transcript) {
		List<String> a = words(text).stream().filter(w -> !LATIN.matcher(w).find()).toList();
		List<String> b = words(transcript);
		if (a.isEmpty()) {
			return new Match(1, Math.max(0, b.size() - 2), 0);
		}
		int[][] lcs = new int[a.size() + 1][b.size() + 1];
		for (int i = 1; i <= a.size(); i++) {
			for (int j = 1; j <= b.size(); j++) {
				lcs[i][j] = same(a.get(i - 1), b.get(j - 1)) ? lcs[i - 1][j - 1] + 1 : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
			}
		}
		int m = lcs[a.size()][b.size()];
		// Слова ника, произнесённые по-русски, — не отсебятина
		int nick = words(text).size() - a.size();
		return new Match((double) m / a.size(), Math.max(0, b.size() - m - nick), a.size());
	}

	/** Одно слово или обрывок: короче — начало длинного, от 4 букв. */
	static boolean same(String x, String y) {
		if (x.equals(y)) {
			return true;
		}
		String s = x.length() <= y.length() ? x : y;
		String l = s == x ? y : x;
		return s.length() >= 4 && l.startsWith(s);
	}

	static List<String> words(String s) {
		String clean = STAGE.matcher(s == null ? "" : s).replaceAll(" ").toLowerCase(Locale.ROOT).replace('ё', 'е');
		clean = STRETCH.matcher(INNER_DASH.matcher(clean).replaceAll("")).replaceAll("$1");
		List<String> out = new ArrayList<>();
		Matcher m = WORD.matcher(clean);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	/** pcm16 LE 24 кГц → 48 кГц для голосового чата: между соседними отсчётами — среднее. */
	public static short[] to48k(byte[] pcm24) {
		int n = pcm24.length / 2;
		short[] out = new short[n * 2];
		for (int i = 0; i < n; i++) {
			short s = (short) ((pcm24[2 * i] & 0xFF) | (pcm24[2 * i + 1] << 8));
			short next = i + 1 < n ? (short) ((pcm24[2 * i + 2] & 0xFF) | (pcm24[2 * i + 3] << 8)) : s;
			out[2 * i] = s;
			out[2 * i + 1] = (short) ((s + next) / 2);
		}
		return out;
	}

	private static JsonObject message(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	private static OpenRouterClient.Usage usage(JsonObject u) {
		int prompt = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsInt() : 0;
		int completion = u.has("completion_tokens") ? u.get("completion_tokens").getAsInt() : 0;
		double cost = u.has("cost") && !u.get("cost").isJsonNull() ? u.get("cost").getAsDouble() : 0;
		return new OpenRouterClient.Usage(prompt, 0, completion, 0, cost);
	}

	private static String clip(String s) {
		return s.length() <= 200 ? s : s.substring(0, 200);
	}
}
