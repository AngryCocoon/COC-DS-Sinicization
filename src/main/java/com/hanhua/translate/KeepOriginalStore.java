package com.hanhua.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hanhua.HanhuaClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "保持原文"的键(模型判定不需要翻译的那些)。
 *
 * 典型例子: {@code jei.key.modifier.shift} = "Shift"、{@code modmenu.discord} = "Discord"、
 * {@code __support_create_stuff} = 模组名 —— DeepSeek 会原样返回英文(这本身就是正确答案),
 * 但旧版本把"译文==原文"当成**失败**去重试 3 轮(每轮一个请求、退避 5/20/60 秒),
 * 用户实测表现为"进度卡着 85 秒不动", 而且每次都白花钱。
 *
 * 现在: 这类键单独记到一个持久化集合里, ① 不再重试 ② 计入完成 ③ 以后扫描直接当"已处理",不再排队。
 * 手动「开始扫描」与 /hanhua clearcache 都**不会**清空它: 再问一遍结论也不会变, 清掉只会让这些键凭空掉回"未译"
 * (无 Key 时根本无法重判, 会永远挂在未译; 0.5.6 用户实测反馈的"扫描后多出 163 条未译"就是这个坑)。
 */
public final class KeepOriginalStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Set<String> keys = ConcurrentHashMap.newKeySet();
	private volatile boolean dirty = false;

	private KeepOriginalStore() { }

	public static Path filePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("hanhua_keep_original.json");
	}

	public static KeepOriginalStore load() {
		KeepOriginalStore store = new KeepOriginalStore();
		Path p = filePath();
		if (!Files.exists(p)) return store;
		try {
			String[] arr = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), String[].class);
			if (arr != null) {
				for (String k : arr) if (k != null && !k.isBlank()) store.keys.add(k);
			}
			HanhuaClient.LOGGER.info("[Hanhua] loaded {} keep-original keys from {}", store.keys.size(), p);
		} catch (Exception e) {
			HanhuaClient.LOGGER.debug("[Hanhua] keep-original file unreadable, starting empty: {}", e.toString());
		}
		return store;
	}

	public boolean contains(String key) {
		return key != null && keys.contains(key);
	}

	public int size() {
		return keys.size();
	}

	/** @return true = 这是个新键(需要落盘) */
	public boolean add(String key) {
		if (key == null || key.isBlank()) return false;
		boolean added = keys.add(key);
		if (added) dirty = true;
		return added;
	}

	public void clear() {
		if (keys.isEmpty()) return;
		keys.clear();
		dirty = true;
	}

	/** 原子落盘(有变化才写) */
	public synchronized void save() {
		if (!dirty) return;
		Path p = filePath();
		try {
			Files.createDirectories(p.getParent());
			Path tmp = p.resolveSibling("hanhua_keep_original.json.tmp");
			Files.writeString(tmp, GSON.toJson(new HashSet<>(keys)), StandardCharsets.UTF_8);
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			HanhuaClient.LOGGER.debug("[Hanhua] failed to save keep-original keys: {}", e.toString());
		}
	}
}
