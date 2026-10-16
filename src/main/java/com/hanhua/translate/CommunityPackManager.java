package com.hanhua.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanhua.HanhuaClient;
import com.hanhua.config.HanhuaConfig;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 社区汉化包融合(P2): 下载 Modrinth 上的社区翻译资源包(CFPA Minecraft-Mod-Language-Package),
 * 解析出 现成译文 表, 供 {@link TranslationManager} 在**扫描/实时查询**时优先命中 ——
 * 社区包已有的键不再调 DeepSeek, AI 只兜底翻译社区包没有的键(省 tokens 的核心机制)。
 *
 * <p>严谨性防护(方案评审定稿):
 * <ul>
 *   <li>下载失败/超时/项目不存在 -> 降级跳过, AI 路线照常, 状态可见({@link #status()});</li>
 *   <li>sha1 与 Modrinth API 返回值比对, 不符丢弃; 与本地已存包一致则跳过下载;</li>
 *   <li>zip 解析只接受 assets 下各命名空间 lang 目录里的语言文件; 条目名含 ".." 拒绝(路径穿越);</li>
 *   <li>单文件 5MB、总条目 50 万上限(zip 炸弹防护); 值必须是字符串 primitive;</li>
 *   <li>默认不覆盖本地已缓存译文(覆盖与否由融合方 {@link TranslationManager} 决定);</li>
 *   <li>来源与协议: CFPA 汉化资源包(CC BY-NC-SA), 在设置界面标注。</li>
 * </ul>
 */
public final class CommunityPackManager {
	public static final CommunityPackManager INSTANCE = new CommunityPackManager();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
	private static final int MAX_ENTRIES = 500_000;

	/** 不可变已发布表; 热路径(get)只读, 更新时整体替换引用 */
	private volatile Map<String, String> table = Map.of();
	private volatile String status = "未启用";
	/** 是否已到达终态(本地包就绪/下载完成/失败/禁用) —— 无 Key 流程判"汉化工作结束"用 */
	private volatile boolean settled = false;
	/** 终态回调(TranslationManager 注册: 无 Key 时补扫吸收现成译文) */
	private volatile Runnable readyListener = null;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private CommunityPackManager() { }

	/** 供融合方查询(渲染热路径安全: volatile 不可变 Map 的 O(1) get) */
	public String lookup(String key) {
		return table.get(key);
	}

	public int size() {
		return table.size();
	}

	public String status() {
		return status;
	}

	/** 是否已到达终态(就绪/失败/禁用都算) —— 无 Key 流程据此判"汉化工作结束, 可以重载资源包" */
	public boolean isSettled() {
		return settled;
	}

	/** 注册终态回调(到达终态时恰好调一次, 在 hanhua-community-pack 线程上) */
	public void setReadyListener(Runnable r) {
		readyListener = r;
	}

	/** 在 dispatcher 线程调用: 异步完成 本地包检测 -> (可选)联网更新 -> 解析 -> 发布, 全程不抛出 */
	public void startAsync(HanhuaConfig cfg) {
		if (!cfg.communityEnabled) {
			status = "已禁用(设置里可开启)";
			settled = true;      // 禁用也算终态: 无 Key 流程不用等它
			return;
		}
		Thread t = new Thread(() -> {
			try {
				refresh(cfg);
			} catch (Throwable e) {
				status = "获取失败: " + brief(e);
				HanhuaClient.LOGGER.warn("[Hanhua] 社区汉化包获取失败(不影响 AI 翻译): {}", e.toString());
			} finally {
				// 无论就绪/失败都算"终态": 通知融合方(无 Key 流程据此判定"汉化工作结束")
				settled = true;
				Runnable r = readyListener;
				if (r != null) {
					try { r.run(); } catch (Throwable ignored) { }
				}
			}
		}, "hanhua-community-pack");
		t.setDaemon(true);
		t.start();
	}

	private void refresh(HanhuaConfig cfg) throws Exception {
		Path zipPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("hanhua").resolve("community-pack.zip");
		Files.createDirectories(zipPath.getParent());

		// 0) 本地包优先: 手动下载的 zip 放进来就直接用(大陆网络到 Modrinth 常不通, 手动放置是可靠通道)
		if (Files.exists(zipPath)) {
			if (!cfg.communityAutoUpdate) {
				publishFromZip(zipPath, cfg);
				status = "本地包 (" + table.size() + " 条)";
				return;
			}
			// autoUpdate 开着: 本地包先解析出来顶上, 再尝试联网更新(失败不影响本地包生效)
			try {
				publishFromZip(zipPath, cfg);
				status = "本地包 (" + table.size() + " 条)";
				HanhuaClient.LOGGER.info("[Hanhua] 社区汉化包(本地): {} 条现成译文", table.size());
			} catch (Exception e) {
				HanhuaClient.LOGGER.warn("[Hanhua] 本地社区包解析失败, 尝试重新下载: {}", e.toString());
			}
			try {
				refreshFromRemote(cfg, zipPath);
			} catch (Throwable e) {
				if (!table.isEmpty()) {
					HanhuaClient.LOGGER.info("[Hanhua] 社区包联网更新不可达(本地包继续生效): {}", brief(e));
				} else {
					status = "获取失败: " + brief(e);
					HanhuaClient.LOGGER.warn("[Hanhua] 社区汉化包获取失败(不影响 AI 翻译): {}", e.toString());
				}
			}
			return;
		}
		if (!cfg.communityAutoUpdate) {
			status = "无本地包, 自动更新已关";
			return;
		}
		refreshFromRemote(cfg, zipPath);
	}

	/** 联网: 查最新版本 -> 与本地比对 -> 下载 -> sha1 校验 -> 解析发布 */
	private void refreshFromRemote(HanhuaConfig cfg, Path zipPath) throws Exception {
		// 1) 查最新版本(Modrinth API)
		status = "检查更新...";
		String slug = blankToDefault(cfg.communityProjectSlug, "minecraft-mod-language-package");
		JsonObject ver = latestVersion(slug);
		if (ver == null) {
			if (table.isEmpty()) status = "未找到可用版本(slug=" + slug + ")";
			return;
		}
		JsonObject file = ver.getAsJsonArray("files").get(0).getAsJsonObject();
		String url = file.get("url").getAsString();
		String sha1 = file.getAsJsonObject("hashes").get("sha1").getAsString();

		// 2) 本地已有且 sha1 一致 -> 直接用(不重复下载)
		Path shaPath = zipPath.resolveSibling("community-pack.zip.sha1");
		if (Files.exists(zipPath) && Files.exists(shaPath)
				&& sha1.equals(Files.readString(shaPath, StandardCharsets.UTF_8).trim())) {
			publishFromZip(zipPath, cfg);
			status = "已是最新(v" + ver.get("version_number").getAsString() + ", " + table.size() + " 条)";
			return;
		}

		// 3) 下载
		status = "下载中...";
		HanhuaClient.LOGGER.info("[Hanhua] 社区汉化包: 下载新版本 v{} ({})", ver.get("version_number").getAsString(), url);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(Math.max(30, cfg.requestTimeoutSeconds)))
				.GET().build();
		HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(zipPath.toFile().toPath()));
		if (resp.statusCode() != 200) {
			status = "下载失败: HTTP " + resp.statusCode();
			return;
		}

		// 4) sha1 校验(不符丢弃, 防止坏包进入解析)
		String actual = sha1Of(zipPath);
		if (!sha1.equalsIgnoreCase(actual)) {
			Files.deleteIfExists(zipPath);
			status = "校验失败(sha1 不符), 已丢弃";
			return;
		}
		Files.writeString(shaPath, sha1, StandardCharsets.UTF_8);

		// 5) 解析并发布
		int n = publishFromZip(zipPath, cfg);
		status = "已加载 v" + ver.get("version_number").getAsString() + " (" + n + " 条)";
		HanhuaClient.LOGGER.info("[Hanhua] 社区汉化包就绪: {} 条现成译文, 扫描与实时查询将优先命中(不调 API)", n);
	}

	/** Modrinth: 项目最新版本(取列表第一个, API 默认按时间倒序); 失败返回 null */
	private JsonObject latestVersion(String slug) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(
					"https://api.modrinth.com/v2/project/" + slug + "/version"))
					.timeout(Duration.ofSeconds(15))
					.header("User-Agent", "hanhua-mod/0.5.0")
					.GET().build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) return null;
			JsonArray arr = GSON.fromJson(resp.body(), JsonArray.class);
			return arr == null || arr.isEmpty() ? null : arr.get(0).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 解析 zip 内 assets/**&#47;lang/<语言>.json 并整体替换已发布表。
	 *
	 * @return 发布条数
	 */
	private int publishFromZip(Path zipPath, HanhuaConfig cfg) throws Exception {
		String lang = blankToDefault(cfg.targetLanguage, "zh_cn") + ".json";
		Map<String, String> m = new ConcurrentHashMap<>();
		try (InputStream in = Files.newInputStream(zipPath); ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
			ZipEntry e;
			byte[] buf = new byte[8192];
			while ((e = zip.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				String name = e.getName().replace('\\', '/');
				if (name.contains("..")) continue;                     // 路径穿越拒绝
				if (!name.startsWith("assets/") || !name.endsWith("/lang/" + lang)) continue;
				if (e.getSize() > MAX_FILE_BYTES) continue;            // 单文件上限(zip 炸弹防护)
				byte[] data = readLimited(zip, buf, MAX_FILE_BYTES);
				if (data == null) continue;
				try {
					JsonObject j = GSON.fromJson(new String(data, StandardCharsets.UTF_8), JsonObject.class);
					if (j == null) continue;
					for (Map.Entry<String, com.google.gson.JsonElement> en : j.entrySet()) {
						if (m.size() >= MAX_ENTRIES) break;
						if (en.getValue().isJsonPrimitive() && !en.getKey().isBlank()) {
							m.putIfAbsent(en.getKey(), en.getValue().getAsString());
						}
					}
				} catch (Exception ignored) { }
			}
		}
		table = Map.copyOf(m);   // 不可变快照整体发布
		return m.size();
	}

	/** 读到上限为止; 超限返回 null(视为坏文件跳过) */
	private static byte[] readLimited(InputStream in, byte[] buf, long limit) throws Exception {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int n;
		long total = 0;
		while ((n = in.read(buf)) > 0) {
			total += n;
			if (total > limit) return null;
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	private static String sha1Of(Path p) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		try (InputStream in = Files.newInputStream(p)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
		}
		return HexFormat.of().formatHex(md.digest());
	}

	private static String blankToDefault(String s, String def) {
		return s == null || s.isBlank() ? def : s.trim();
	}

	private static String brief(Throwable t) {
		String s = t.toString();
		return s.length() > 80 ? s.substring(0, 80) : s;
	}
}
