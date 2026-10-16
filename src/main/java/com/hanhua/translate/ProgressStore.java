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

/**
 * 汉化进度的持久化(config/hanhua_progress.json)。
 * 这样开始菜单左上角不用等本次扫描就能直接显示百分数(上次会话的统计)。
 *
 * 0.4.3 起口径: zhCovered = 扫描时已经有中文的键; missing = 确实没中文、需要翻译的键;
 * resolved/skipped = 其中已译好/已放弃的; scope = 承诺翻译的命名空间(下次会话续译, 保证"开始译了就译完")。
 */
public final class ProgressStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private ProgressStore() { }

	public static Path filePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("hanhua_progress.json");
	}

	public static final class Snapshot {
		public long namespaces;
		public long enKeys;
		public long zhCovered;
		public long missing;
		/** 进度分子/分母: 已经译好的条数 / 本次要译的总条数(累加值, 重启后从上次的位置继续) */
		public long workDone;
		public long workTotal;
		public long skipped;
		public long queued;
		public long modEn;
		public long modZh;
		public java.util.List<String> scope;
		/** 还没出结果的键(存盘截断到 2 万条): 重启后恢复 workKeys, 防"恢复后误判干完 -> 空 scope 覆盖快照" */
		public java.util.List<String> unfinishedKeys;
		public boolean incomplete;
		public long at;
	}

	public static Snapshot load() {
		Path p = filePath();
		if (!Files.exists(p)) return null;
		try {
			Snapshot s = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Snapshot.class);
			return (s == null || s.at <= 0) ? null : s;
		} catch (Exception e) {
			HanhuaClient.LOGGER.debug("[Hanhua] progress snapshot unreadable: {}", e.toString());
			return null;
		}
	}

	public static void save(long namespaces, long enKeys, long zhCovered, long missing,
			long workTotal, long workDone, long skipped, long queued, long modEn, long modZh,
			java.util.Collection<String> scope, java.util.Collection<String> unfinished, boolean incomplete) {
		Path p = filePath();
		try {
			Files.createDirectories(p.getParent());
			Snapshot s = new Snapshot();
			s.namespaces = namespaces;
			s.enKeys = enKeys;
			s.zhCovered = zhCovered;
			s.missing = missing;
			s.workTotal = workTotal;
			s.workDone = workDone;
			s.skipped = skipped;
			s.queued = queued;
			s.modEn = modEn;
			s.modZh = modZh;
			s.scope = scope == null ? java.util.List.of() : new java.util.ArrayList<>(scope);
			s.unfinishedKeys = unfinished == null ? java.util.List.of()
					: unfinished.size() > 20000
					? new java.util.ArrayList<>(unfinished).subList(0, 20000)
					: new java.util.ArrayList<>(unfinished);
			s.incomplete = incomplete;
			s.at = System.currentTimeMillis();
			Path tmp = p.resolveSibling("hanhua_progress.json.tmp");
			Files.writeString(tmp, GSON.toJson(s), StandardCharsets.UTF_8);
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			HanhuaClient.LOGGER.debug("[Hanhua] failed to save progress snapshot: {}", e.toString());
		}
	}

	/** 某个命名空间在上次快照里是否还有没译完的键 */
	public static boolean hasUnfinished(Snapshot s) {
		return s != null && (s.incomplete || s.scope != null && !s.scope.isEmpty()
				|| s.unfinishedKeys != null && !s.unfinishedKeys.isEmpty());
	}
}
