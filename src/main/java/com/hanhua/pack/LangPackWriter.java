package com.hanhua.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hanhua.HanhuaClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把翻译缓存持久化成"含语言的资源包"(参照 i18n 汉化模组的做法):
 *
 *   .minecraft/resourcepacks/COC-DS-Sinicization-zh_cn/
 *     ├── pack.mcmeta
 *     └── assets/&lt;namespace&gt;/lang/zh_cn.json
 *
 * 资源包内的 lang 文件会被原版 {@code ClientLanguage.loadFrom} 与所有其它 lang 文件合并进
 * 同一张 key→value 表,所以即使命名空间划分不准确,翻译依然生效。
 *
 * 关于"不重复加载资源包"(用户反馈):
 * - 写入前先算内容指纹,和 config/hanhua_pack.json 里记录的一致就**完全不写盘**;
 * - 自动启用只做"写进 options.txt 的 resourcePacks 列表",**不触发 reloadResourcePacks**;
 *   该资源包会在下一次正常的资源加载(下次启动的初始加载)里一次性生效;
 * - 运行期新翻译出来的键由 mixin 即时生效,不需要重载;
 * - 只有手动 `/hanhua pack` 才会强制重写并立刻重载一次。
 */
public final class LangPackWriter {
	public static final String PACK_NAME = "COC-DS-Sinicization-zh_cn";
	public static final String PACK_ID = "file/" + PACK_NAME;
	public static final String FALLBACK_NAMESPACE = "hanhua";
	/** writeAll 返回值: 内容指纹未变, 已跳过写盘 */
	public static final int UNCHANGED = -2;
	public static final String PACK_DESCRIPTION = "COC DS Sinicization 自动汉化资源包 (DeepSeek)";
	/**
	 * pack.mcmeta 里的格式上限写成一个很大的值: 只写"当前版本号"的话, 将来游戏升级
	 * (客户端资源包格式号变大)时, 已经生成好的资源包会被判定"不兼容"而自动停用 ——
	 * 尤其是 mod 已被删掉、没人再来补写 mcmeta 的情况, 汉化就"消失"了。
	 * 26.3 实测: max_format=9999 时游戏照常加载该资源包, 资源包界面也不报兼容性警告。
	 */
	private static final int MAX_PACK_FORMAT = 9999;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<String, String> LAST_WRITTEN = new ConcurrentHashMap<>();

	private static volatile Set<String> namespaceCache;
	private static volatile int lastKeyCount;
	private static volatile int lastNamespaceCount;
	private static volatile String lastError;
	/**
	 * 游戏启动时(模组初始化那一刻)资源包文件夹是否存在。
	 * 用来区分"用户手动关掉了资源包"和"文件夹被删了、游戏自动把它从 options.txt 里摘掉"这两种情况:
	 * 前者要尊重用户, 后者必须重新启用(用户实测: 删掉资源包后重译, 包虽然重建了但一直没被启用)。
	 */
	private static volatile Boolean packExistedAtStartup = null;

	private LangPackWriter() { }

	public static Path packDir() {
		return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK_NAME);
	}

	public static Path metaFile() {
		return packDir().resolve("pack.mcmeta");
	}

	/** 资源包状态文件(放在 config 下, 保持资源包目录干净) */
	public static Path stateFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("hanhua_pack.json");
	}

	public static int lastKeyCount() { return lastKeyCount; }
	public static int lastNamespaceCount() { return lastNamespaceCount; }
	public static String lastError() { return lastError; }

	/** 模组初始化时调用一次: 记下"启动那一刻资源包在不在" */
	public static void noteStartupState() {
		boolean exists = hasPackFiles();
		packExistedAtStartup = exists;
		HanhuaClient.LOGGER.info("[Hanhua] 启动时汉化资源包{}: {}", exists ? "存在" : "不存在(将被重建)", packDir());
	}

	/** 资源包文件夹中途不见了(用户删了) -> 需要重建 */
	public static boolean missingNow() {
		return !hasPackFiles();
	}

	/**
	 * 把整张缓存写成资源包。线程安全,可在后台线程调用。
	 * 内容指纹没变时**完全不写盘**(因此也不会引发任何资源重载)。
	 *
	 * @return 写入的命名空间数量;{@link #UNCHANGED} 表示内容未变已跳过;0 表示没有内容;-1 表示失败
	 */
	public static synchronized int writeAll(Map<String, String> entries, String language) {
		if (entries == null || entries.isEmpty()) {
			lastKeyCount = 0;
			lastNamespaceCount = 0;
			return 0;
		}
		String lang = (language == null || language.isBlank()) ? "zh_cn" : language.trim();
		try {
			Map<String, Map<String, String>> byNamespace = new TreeMap<>();
			for (Map.Entry<String, String> e : entries.entrySet()) {
				String k = e.getKey();
				String v = e.getValue();
				if (k == null || k.isBlank() || v == null || v.isEmpty()) continue;
				byNamespace.computeIfAbsent(namespaceOf(k), n -> new TreeMap<>()).put(k, v);
			}
			if (byNamespace.isEmpty()) return 0;

			String fingerprint = fingerprint(byNamespace, lang);
			String meta = metaJson();
			PackState state = PackState.load();
			// pack.mcmeta 也要一起检查: 旧版本写的格式范围(或损坏的文件)会自动升级重写
			if (fingerprint.equals(state.fingerprint) && metaFileMatches(meta)) {
				lastKeyCount = state.keys;
				lastNamespaceCount = state.namespaces;
				return UNCHANGED;
			}

			Path dir = packDir();
			Files.createDirectories(dir);
			writeText(metaFile(), meta);

			int keys = 0;
			Set<Path> keep = new HashSet<>();
			for (Map.Entry<String, Map<String, String>> ns : byNamespace.entrySet()) {
				Path file = dir.resolve("assets").resolve(ns.getKey()).resolve("lang").resolve(lang + ".json");
				keep.add(file.toAbsolutePath().normalize());
				writeText(file, GSON.toJson(new TreeMap<>(ns.getValue())));
				keys += ns.getValue().size();
			}
			pruneStale(dir, lang, keep);

			lastKeyCount = keys;
			lastNamespaceCount = byNamespace.size();
			lastError = null;
			state.fingerprint = fingerprint;
			state.keys = keys;
			state.namespaces = byNamespace.size();
			state.updatedAt = System.currentTimeMillis();
			state.save();
			return byNamespace.size();
		} catch (Throwable t) {
			lastError = String.valueOf(t);
			HanhuaClient.LOGGER.warn("[Hanhua] failed to write language resource pack", t);
			return -1;
		}
	}

	/**
	 * 删除整个资源包目录。**唯一**调用点是 TranslationManager.clearCache()(即用户手动执行 /hanhua clearcache)。
	 *
	 * 关于"删掉 mod 之后资源包还在不在"(用户要求):
	 * - 资源包是写在 <游戏目录>/resourcepacks/COC-DS-Sinicization-zh_cn 的**普通文件夹**,
	 *   不是从 mod jar 里读的, 也没有任何"卸载时清理"的逻辑;
	 * - 把 mods/*.jar 删掉(或换电脑、换版本、重装 mod)时, 这里一行代码都不会跑,
	 *   文件夹、options.txt 里选中它的记录、config/hanhua_pack.json 全部原样保留,
	 *   游戏照常加载并显示汉化;
	 * - 反过来, 只要 mod 还在, 删掉资源包也能凭缓存自动重建(指纹对不上就会重写)。
	 */
	public static synchronized void deletePack() {
		Path dir = packDir();
		try {
			deleteRecursively(dir);
			LAST_WRITTEN.clear();
			lastKeyCount = 0;
			lastNamespaceCount = 0;
			lastError = null;
			PackState st = PackState.load();
			st.fingerprint = "";
			st.save();
			HanhuaClient.LOGGER.info("[Hanhua] language resource pack removed: {}", dir);
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to remove language resource pack {}", dir, t);
		}
	}

	private static void deleteRecursively(Path p) throws IOException {
		if (!Files.exists(p)) return;
		if (Files.isDirectory(p)) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
				for (Path child : ds) deleteRecursively(child);
			}
		}
		Files.deleteIfExists(p);
	}

	/** 当前游戏版本对应的资源包格式(26.x 起 pack.mcmeta 用 min_format/max_format) */
	private static String metaJson() {
		int major;
		try {
			major = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
		} catch (Throwable t) {
			major = 0;
		}
		JsonObject pack = new JsonObject();
		pack.addProperty("description", PACK_DESCRIPTION);
		if (major > 0) {
			// min_format 只写 major: 原版 PackFormat.minorRange() 会把"仅 major"自动放宽到该 major 的全部 minor;
			// max_format 写大值 -> 将来游戏升级也不用重写这个文件, 资源包不会因为"版本不兼容"被停用
			pack.addProperty("min_format", major);
			pack.addProperty("max_format", MAX_PACK_FORMAT);
		} else {
			pack.addProperty("pack_format", 15);
		}
		JsonObject root = new JsonObject();
		root.add("pack", pack);
		return GSON.toJson(root);
	}

	/** 盘上的 pack.mcmeta 是否就是我们此刻要写的那份(不是或读不出来 -> 需要重写) */
	private static boolean metaFileMatches(String expected) {
		try {
			return Files.isRegularFile(metaFile())
					&& expected.equals(Files.readString(metaFile(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return false;
		}
	}

	/** 删除本资源包内已不再需要的旧 lang 文件(只动我们自己的包目录) */
	private static void pruneStale(Path dir, String lang, Set<Path> keep) {
		Path assets = dir.resolve("assets");
		if (!Files.isDirectory(assets)) return;
		List<Path> stale = new ArrayList<>();
		try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(assets)) {
			for (Path ns : namespaces) {
				if (!Files.isDirectory(ns)) continue;
				Path langDir = ns.resolve("lang");
				if (!Files.isDirectory(langDir)) continue;
				try (DirectoryStream<Path> files = Files.newDirectoryStream(langDir, lang + ".json")) {
					for (Path f : files) {
						if (!keep.contains(f.toAbsolutePath().normalize())) stale.add(f);
					}
				}
			}
		} catch (IOException e) {
			HanhuaClient.LOGGER.debug("[Hanhua] prune skipped: {}", e.toString());
		}
		for (Path f : stale) {
			try {
				Files.deleteIfExists(f);
			} catch (IOException ignored) { }
		}
	}

	private static void writeText(Path file, String content) throws IOException {
		String id = file.toAbsolutePath().normalize().toString();
		if (content.equals(LAST_WRITTEN.get(id)) && Files.exists(file)) return;
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
		LAST_WRITTEN.put(id, content);
	}

	/** 全部译文的内容指纹(命名空间+键+值+语言),用于判断"资源包有没有变" */
	private static String fingerprint(Map<String, Map<String, String>> byNamespace, String lang) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			md.update(lang.getBytes(StandardCharsets.UTF_8));
			for (Map.Entry<String, Map<String, String>> ns : byNamespace.entrySet()) {
				md.update((byte) 0);
				md.update(ns.getKey().getBytes(StandardCharsets.UTF_8));
				for (Map.Entry<String, String> kv : ns.getValue().entrySet()) {
					md.update((byte) 1);
					md.update(kv.getKey().getBytes(StandardCharsets.UTF_8));
					md.update((byte) 2);
					md.update(kv.getValue().getBytes(StandardCharsets.UTF_8));
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (Throwable t) {
			// 极端情况下退化成"内容总量+时间无关的粗略指纹"
			int h = 17;
			for (Map.Entry<String, Map<String, String>> ns : byNamespace.entrySet()) {
				h = h * 31 + ns.getKey().hashCode();
				for (Map.Entry<String, String> kv : ns.getValue().entrySet()) {
					h = h * 31 + kv.getKey().hashCode();
					h = h * 31 + kv.getValue().hashCode();
				}
			}
			return "h" + h;
		}
	}

	/** 翻译键所属命名空间: 优先取 key 前缀(若确实是已加载 mod / minecraft),否则归到 hanhua */
	private static String namespaceOf(String key) {
		int dot = key.indexOf('.');
		if (dot > 0) {
			String candidate = key.substring(0, dot);
			if (namespaces().contains(candidate)) return candidate;
		}
		return FALLBACK_NAMESPACE;
	}

	private static Set<String> namespaces() {
		Set<String> cached = namespaceCache;
		if (cached != null) return cached;
		Set<String> set = new HashSet<>();
		set.add("minecraft");
		set.add(FALLBACK_NAMESPACE);
		try {
			FabricLoader.getInstance().getAllMods().forEach(m -> set.add(m.getMetadata().getId()));
		} catch (Throwable ignored) { }
		namespaceCache = set;
		return set;
	}

	/** 资源包是否已在 options 里被选中(= 下次/本次启动的正常资源加载会带上它) */
	public static boolean isEnabled() {
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc != null && mc.options != null && mc.options.resourcePacks.contains(PACK_ID);
		} catch (Throwable t) {
			return false;
		}
	}

	/** 资源包文件是否已经写好 */
	public static boolean hasPackFiles() {
		return Files.isRegularFile(metaFile());
	}

	/**
	 * 只把资源包写进 options.txt 的 resourcePacks 列表并保存,**不重载资源**
	 * (必须在客户端主线程调用)。真正的加载交给下一次正常的资源加载。
	 *
	 * @return true = 本次新写入(下次启动生效);false = 已经是选中状态 / 用户手动关掉过 / 失败
	 */
	public static boolean autoEnableWithoutReload() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) return false;
		if (!hasPackFiles()) {
			lastError = "pack.mcmeta missing";
			return false;
		}
		try {
			PackState state = PackState.load();
			if (mc.options.resourcePacks.contains(PACK_ID)) {
				state.autoEnabled = true;     // 已经是选中状态(含用户自己勾的)
				state.save();
				return false;
			}
			if (state.autoEnabled && !Boolean.FALSE.equals(packExistedAtStartup)) {
				// 我们之前自动启用过, 现在却不在列表里 -> 用户手动关掉了, 尊重用户选择
				HanhuaClient.LOGGER.info("[Hanhua] resource pack was disabled by the user, not re-enabling");
				return false;
			}
			if (state.autoEnabled) {
				// 启动时资源包文件夹根本不存在 -> 游戏自己把它从 options 里摘掉了, 不是用户的意愿, 重新启用
				HanhuaClient.LOGGER.info("[Hanhua] 启动时资源包缺失, 游戏把它从 options 里摘掉了 -> 重新启用");
			}
			mc.options.resourcePacks.add(PACK_ID);
			mc.options.save();
			state.autoEnabled = true;
			state.save();
			lastError = null;
			HanhuaClient.LOGGER.info("[Hanhua] language resource pack registered in options.txt (no reload; "
					+ "it will be loaded with the next normal resource load)");
			return true;
		} catch (Throwable t) {
			lastError = String.valueOf(t);
			HanhuaClient.LOGGER.warn("[Hanhua] failed to register language resource pack in options", t);
			return false;
		}
	}

	/**
	 * 手动路径(`/hanhua pack`): 加入选中列表并**立刻重载一次**资源, 让资源包马上就生效。
	 * 必须在客户端主线程调用。
	 */
	public static boolean enableAndReloadNow() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) return false;
		if (!hasPackFiles()) {
			lastError = "pack.mcmeta missing";
			return false;
		}
		try {
			PackRepository repo = mc.getResourcePackRepository();
			if (repo == null) return false;
			repo.reload();
			if (!repo.isAvailable(PACK_ID)) {
				lastError = "pack not discovered by PackRepository: " + PACK_ID;
				HanhuaClient.LOGGER.warn("[Hanhua] {}", lastError);
				return false;
			}
			List<String> selected = mc.options.resourcePacks;
			repo.addPack(PACK_ID);
			if (!repo.getSelectedIds().contains(PACK_ID)) {
				lastError = "PackRepository refused selection: " + PACK_ID;
				HanhuaClient.LOGGER.warn("[Hanhua] {}", lastError);
				return false;
			}
			mc.options.updateResourcePacks(repo);
			if (!mc.options.resourcePacks.contains(PACK_ID)) selected.add(PACK_ID);
			mc.options.save();
			PackState state = PackState.load();
			state.autoEnabled = true;
			state.save();
			mc.reloadResourcePacks();
			lastError = null;
			HanhuaClient.LOGGER.info("[Hanhua] language resource pack enabled + reloaded once: {}", PACK_ID);
			return true;
		} catch (Throwable t) {
			lastError = String.valueOf(t);
			HanhuaClient.LOGGER.warn("[Hanhua] failed to enable language resource pack", t);
			return false;
		}
	}

	/** config/hanhua_pack.json: 记录资源包指纹与"是否自动启用过" */
	private static final class PackState {
		String fingerprint = "";
		int keys;
		int namespaces;
		long updatedAt;
		boolean autoEnabled;

		static PackState load() {
			Path p = stateFile();
			if (!Files.exists(p)) return new PackState();
			try {
				PackState st = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), PackState.class);
				return st == null ? new PackState() : st;
			} catch (Exception e) {
				HanhuaClient.LOGGER.debug("[Hanhua] pack state unreadable, rebuilding: {}", e.toString());
				return new PackState();
			}
		}

		void save() {
			Path p = stateFile();
			try {
				Files.createDirectories(p.getParent());
				Path tmp = p.resolveSibling("hanhua_pack.json.tmp");
				Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
				Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				HanhuaClient.LOGGER.debug("[Hanhua] failed to save pack state: {}", e.toString());
			}
		}
	}
}
