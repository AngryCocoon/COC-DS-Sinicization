package com.hanhua.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hanhua.HanhuaClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义词典(P1): config/hanhua_glossary.json, 格式 {"Enderman": "末影人"}(英文原文 -> 强制中文)。
 *
 * <p>生效方式: 只影响**新翻译** —— 每次批量翻译前, 用批内英文文本匹配词典,
 * 把命中的术语追加进请求(见 {@link DeepSeekApi}), 强制模型用指定译名。
 * 已缓存/资源包里已有的译文不受影响(要用新译名请用设置里的手动重扫)。
 *
 * <p>文件不存在 = 空词典, 静默。文件可随时编辑, 下一次请求自动生效(mtime 检查, 零成本热更新)。
 */
public final class Glossary {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Object LOCK = new Object();
	/** en -> zh 强制对照表 */
	private static volatile Map<String, String> table = Map.of();
	private static volatile long lastLoadAt = 0L;
	private static volatile Path filePath = null;

	private Glossary() { }

	/** 启动时调用一次; 之后 termsFor 内部按 mtime 自动重载 */
	public static void load() {
		filePath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hanhua_glossary.json");
		reload(true);
	}

	private static void reload(boolean force) {
		synchronized (LOCK) {
			try {
				Path p = filePath;
				if (p == null || !Files.exists(p)) {
					if (force && table.isEmpty()) return; // 首次且无文件: 静默
					table = Map.of();
					return;
				}
				long mtime = Files.getLastModifiedTime(p).toMillis();
				if (!force && mtime == lastLoadAt) return; // 没变
				lastLoadAt = mtime;
				JsonObject root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
				if (root == null) return;
				Map<String, String> m = new ConcurrentHashMap<>();
				root.entrySet().forEach(e -> {
					if (e.getValue().isJsonPrimitive() && !e.getKey().isBlank()) {
						m.put(e.getKey(), e.getValue().getAsString());
					}
				});
				table = m;
				if (!m.isEmpty()) {
					HanhuaClient.LOGGER.info("[Hanhua] 自定义词典已加载: {} 条 ({})", m.size(), p);
				}
			} catch (Exception e) {
				HanhuaClient.LOGGER.warn("[Hanhua] 自定义词典加载失败(忽略, 走普通翻译): {}", e.toString());
			}
		}
	}

	/**
	 * 批量翻译前取"本批相关的术语"。
	 *
	 * @return 可直接追加进请求的提示文本; 本批没有任何术语命中时返回 null(零额外 tokens)
	 */
	public static String termsFor(Collection<String> englishTexts) {
		reload(false);
		Map<String, String> m = table;
		if (m.isEmpty() || englishTexts == null || englishTexts.isEmpty()) return null;
		StringBuilder sb = null;
		for (Map.Entry<String, String> t : m.entrySet()) {
			String en = t.getKey();
			for (String text : englishTexts) {
				if (text != null && text.contains(en)) {
					if (sb == null) sb = new StringBuilder("术语表(命中词条必须使用指定译名, 不得改动):\n");
					sb.append(en).append(" -> ").append(t.getValue()).append('\n');
					break;
				}
			}
		}
		return sb == null ? null : sb.toString();
	}

	/** 词典条数(状态显示用) */
	public static int size() {
		reload(false);
		return table.size();
	}
}
