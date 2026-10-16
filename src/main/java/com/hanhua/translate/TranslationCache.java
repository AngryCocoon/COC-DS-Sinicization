package com.hanhua.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hanhua.HanhuaClient;
import com.hanhua.config.HanhuaConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译键→译文 的内存+磁盘缓存。
 * 存储位置: config/hanhua_translations.json(与 mod 配置同目录)。
 */
public final class TranslationCache {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int SCHEMA = 1;

	private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();
	private volatile boolean dirty = false;

	public static Path filePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("hanhua_translations.json");
	}

	public String get(String key) {
		return key == null ? null : entries.get(key);
	}

	public void putAll(Map<String, String> map) {
		if (map == null || map.isEmpty()) return;
		entries.putAll(map);
		dirty = true;
	}

	public int size() {
		return entries.size();
	}

	/** 只读快照(用于写资源包) */
	public Map<String, String> snapshot() {
		return new java.util.HashMap<>(entries);
	}

	/** 超出上限时裁剪(近似丢弃最旧;超上限较多时一次性收敛) */
	public void trim(int max) {
		if (max <= 0 || entries.size() <= max) return;
		Iterator<String> it = entries.keySet().iterator();
		while (it.hasNext() && entries.size() > max) {
			it.next();
			it.remove();
		}
		dirty = true;
	}

	public void clear() {
		if (entries.isEmpty()) return;
		entries.clear();
		dirty = true;
	}

	public static TranslationCache load() {
		TranslationCache cache = new TranslationCache();
		Path p = filePath();
		if (!Files.exists(p)) return cache;
		boolean broken = false;
		try {
			JsonObject root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null || !root.has("entries")) {
				// JSON 合法但结构不对(schema 不识别/被外部工具改坏): 与解析异常同等对待 —— 告警 + 备份 + 自愈
				HanhuaClient.LOGGER.warn("[Hanhua] translation cache has unexpected structure, moving aside: {}", p);
				broken = true;
			} else {
				JsonObject en = root.getAsJsonObject("entries");
				en.entrySet().forEach(e -> {
					if (e.getValue().isJsonPrimitive()) cache.entries.put(e.getKey(), e.getValue().getAsString());
				});
				HanhuaClient.LOGGER.info("[Hanhua] loaded {} cached translations from {}", cache.entries.size(), p);
			}
		} catch (Exception e) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to load translation cache from {}, ignoring", p, e);
			broken = true;
		}
		if (broken) {
			try {
				Files.move(p, p.resolveSibling("hanhua_translations.json.bak"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) { }
		}
		// 自愈: 缓存为空而资源包在 -> 从包回填。包与缓存互为唯一副本, 双双丢失会触发全量重译(API 费用);
		// 包是磁盘上更持久的副本, 用它恢复缓存(真机实证: 缓存被清空后从包找回全部 298 条)。
		if (cache.entries.isEmpty()) {
			int n = restoreFromLangPack(cache);
			if (n > 0) {
				HanhuaClient.LOGGER.warn("[Hanhua] 缓存为空 -> 从资源包回填 {} 条译文(下次保存时重建缓存文件)", n);
				cache.dirty = true;
			}
		}
		return cache;
	}

	/** 从资源包各命名空间目录下的 zh_cn.json 读回全部译文(自愈用); 返回读到的条目数, 失败返回 0 */
	private static int restoreFromLangPack(TranslationCache cache) {
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("resourcepacks")
					.resolve(com.hanhua.pack.LangPackWriter.PACK_NAME).resolve("assets");
			if (!Files.isDirectory(dir)) return 0;
			String lang0 = "zh_cn";
			try {
				String l = HanhuaConfig.INSTANCE.targetLanguage;
				if (l != null && !l.isBlank()) lang0 = l.trim();
			} catch (Throwable ignored) { }
			final String lang = lang0;
			int n = 0;
			try (var walk = Files.walk(dir)) {
				var files = walk.filter(f -> f.getFileName().toString().equals(lang + ".json")).iterator();
				while (files.hasNext()) {
					try {
						JsonObject j = GSON.fromJson(Files.readString(files.next(), StandardCharsets.UTF_8), JsonObject.class);
						if (j == null) continue;
						for (var e : j.entrySet()) {
							if (e.getValue().isJsonPrimitive()) {
								cache.entries.putIfAbsent(e.getKey(), e.getValue().getAsString());
								n++;
							}
						}
					} catch (Exception ignored) { }
				}
			}
			return n;
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] restore-from-pack skipped: {}", t.toString());
			return 0;
		}
	}

	/** 原子写回;无改动且文件已存在时跳过 */
	public synchronized void save() {
		Path p = filePath();
		boolean needWrite = dirty || !Files.exists(p);
		if (!needWrite) return;
		try {
			Files.createDirectories(p.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			root.add("entries", GSON.toJsonTree(entries));
			Path tmp = p.resolveSibling("hanhua_translations.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to save translation cache to {}", p, e);
		}
	}
}
