package ru.xetpy.rikoshet.core;

import java.nio.file.Path;

/**
 * Где мод хранит файлы. dataDir — {@code <сервер>/rikoshet/}: БД, секреты, переопределения
 * промптов и заготовок. Это не папка мира: данные общие для всех измерений.
 */
public record ModPaths(Path serverDir, Path configFile, Path dataDir) {
	public static ModPaths of(Path serverDir, Path configDir) {
		return new ModPaths(serverDir, configDir.resolve("rikoshet.json5"), serverDir.resolve("rikoshet"));
	}

	public Path resolveFromServer(String relative) {
		return serverDir.resolve(relative).normalize();
	}
}
