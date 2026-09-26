package ru.xetpy.rikoshet.storage;

import org.slf4j.Logger;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite в режиме WAL. Все запросы — в одном потоке «rikoshet-db», главный поток к БД
 * не обращается (кроме загрузки при старте сервера, до первого тика).
 */
public final class Database implements AutoCloseable {
	/** Миграции по порядку; файлы — rikoshet/db/migrations/<имя>.sql. Номер версии = позиция + 1. */
	static final List<String> MIGRATIONS = List.of("0001_init", "0002_chronicle", "0003_builds", "0004_pools");

	@FunctionalInterface
	public interface Work<T> {
		T run(Connection c) throws SQLException;
	}

	@FunctionalInterface
	public interface Task {
		void run(Connection c) throws SQLException;
	}

	private final Logger log;
	private final ExecutorService thread;
	private final Connection conn;

	private Database(Logger log, ExecutorService thread, Connection conn) {
		this.log = log;
		this.thread = thread;
		this.conn = conn;
	}

	public static Database open(Path file, Logger log) throws SQLException, IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "rikoshet-db");
			t.setDaemon(true);
			return t;
		});
		try {
			Connection c = thread.submit(() -> connect(file)).get();
			Database db = new Database(log, thread, c);
			db.call(db::migrate);
			return db;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			thread.shutdownNow();
			throw new SQLException("прервано при открытии БД", e);
		} catch (ExecutionException e) {
			thread.shutdownNow();
			if (e.getCause() instanceof SQLException s) {
				throw s;
			}
			throw new SQLException("БД не открылась", e.getCause());
		}
	}

	private static Connection connect(Path file) throws SQLException {
		SQLiteConfig cfg = new SQLiteConfig();
		cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
		cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
		cfg.setBusyTimeout(5000);
		cfg.enforceForeignKeys(true);
		// Драйвер берём напрямую: DriverManager внутри Fabric может не увидеть вложенный jar
		return cfg.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
	}

	private Integer migrate(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL)");
		}
		int current = 0;
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
			if (rs.next()) {
				current = rs.getInt(1);
			}
		}
		if (current > MIGRATIONS.size()) {
			throw new SQLException("схема БД версии " + current + " новее мода (" + MIGRATIONS.size() + "): мод откатили?");
		}
		for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
			String name = MIGRATIONS.get(v - 1);
			String sql = resource("/rikoshet/db/migrations/" + name + ".sql");
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try (Statement st = c.createStatement()) {
				for (String stmt : split(sql)) {
					st.execute(stmt);
				}
				st.execute("INSERT INTO schema_version (version, applied_at) VALUES (" + v + ", " + System.currentTimeMillis() + ")");
				c.commit();
				log.info("[БД] миграция {} применена", name);
			} catch (SQLException e) {
				c.rollback();
				throw new SQLException("миграция " + name + ": " + e.getMessage(), e);
			} finally {
				c.setAutoCommit(auto);
			}
		}
		return MIGRATIONS.size();
	}

	/** Делит SQL-файл на команды по «;» в конце строки; комментарии «--» выкидывает. */
	static List<String> split(String sql) {
		StringBuilder clean = new StringBuilder();
		for (String line : sql.split("\n")) {
			int dash = line.indexOf("--");
			clean.append(dash >= 0 ? line.substring(0, dash) : line).append('\n');
		}
		return java.util.Arrays.stream(clean.toString().split(";\\s*\n"))
				.map(String::strip)
				.map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s)
				.filter(s -> !s.isEmpty())
				.toList();
	}

	private static String resource(String name) {
		try (InputStream in = Database.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException("нет ресурса " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Запрос с результатом. */
	public <T> CompletableFuture<T> submit(Work<T> work) {
		CompletableFuture<T> f = new CompletableFuture<>();
		try {
			thread.execute(() -> {
				try {
					f.complete(work.run(conn));
				} catch (Throwable t) {
					f.completeExceptionally(t);
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			f.completeExceptionally(e);
		}
		return f;
	}

	/** Запись «выстрелил и забыл»: ошибка уходит в лог. */
	public void execute(String what, Task task) {
		submit(c -> {
			task.run(c);
			return null;
		}).exceptionally(t -> {
			log.warn("[БД] {}: {}", what, t.toString());
			return null;
		});
	}

	/** Синхронный вызов — только при старте и остановке сервера. */
	public <T> T call(Work<T> work) throws SQLException {
		try {
			return submit(work).get(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SQLException("прервано", e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof SQLException s) {
				throw s;
			}
			throw new SQLException(e.getCause());
		} catch (java.util.concurrent.TimeoutException e) {
			throw new SQLException("БД не ответила за 30 с", e);
		}
	}

	/** Дожидается всех записей и закрывает соединение. */
	@Override
	public void close() {
		thread.execute(() -> {
			try {
				conn.close();
			} catch (SQLException e) {
				log.warn("[БД] закрытие: {}", e.toString());
			}
		});
		thread.shutdown();
		try {
			if (!thread.awaitTermination(10, TimeUnit.SECONDS)) {
				log.warn("[БД] не все записи успели за 10 с");
				thread.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
