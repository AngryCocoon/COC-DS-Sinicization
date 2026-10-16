package com.hanhua.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hanhua.HanhuaClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 已记录模组清单(modid → version)。用于"只在检测到新增/更新模组时才调用 API"。
 * 持久化到 config/hanhua_known_mods.json。
 */
public final class ModInventory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final int SCHEMA = 1;
	/** 本模组自身不参与比对,否则每次升级 jar 都会触发一次全量扫描 */
	private static final Set<String> IGNORED = Set.of("hanhua");

	private final TreeMap<String, String> known = new TreeMap<>();
	private final boolean firstRun;
	private volatile boolean dirty;

	private ModInventory(boolean firstRun) {
		this.firstRun = firstRun;
	}

	public static Path filePath() {
		return FabricLoader.getInstance().getConfigDir().resolve("hanhua_known_mods.json");
	}

	public static ModInventory load() {
		Path p = filePath();
		boolean exists = Files.exists(p);
		ModInventory inv = new ModInventory(!exists);
		if (!exists) return inv;
		try {
			JsonObject root = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), JsonObject.class);
			if (root != null && root.has("mods") && root.get("mods").isJsonObject()) {
				for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("mods").entrySet()) {
					if (e.getValue().isJsonPrimitive()) inv.known.put(e.getKey(), e.getValue().getAsString());
				}
			}
			HanhuaClient.LOGGER.info("[Hanhua] loaded known-mod inventory ({} mods) from {}", inv.known.size(), p);
		} catch (Exception e) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to read {}, treating all mods as new", p, e);
		}
		return inv;
	}

	public boolean isFirstRun() {
		return firstRun;
	}

	public int knownCount() {
		return known.size();
	}

	/** 当前实际加载的 modid → 版本 */
	public static TreeMap<String, String> currentMods() {
		TreeMap<String, String> now = new TreeMap<>();
		try {
			for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
				String id = m.getMetadata().getId();
				if (IGNORED.contains(id)) continue;
				String ver = m.getMetadata().getVersion() == null ? "?" : m.getMetadata().getVersion().getFriendlyString();
				now.put(id, ver);
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to enumerate mods", t);
		}
		return now;
	}

	/** 相比上次记录,新增或版本变化的 modid 集合(首次运行 = 全部) */
	public Set<String> newOrUpdated() {
		TreeMap<String, String> now = currentMods();
		Set<String> changed = new LinkedHashSet<>();
		for (Map.Entry<String, String> e : now.entrySet()) {
			String old = known.get(e.getKey());
			if (old == null || !old.equals(e.getValue())) changed.add(e.getKey());
		}
		return changed;
	}

	/** 本次变化详情(用于日志/命令输出) */
	public String describeChanges() {
		TreeMap<String, String> now = currentMods();
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> e : now.entrySet()) {
			String old = known.get(e.getKey());
			if (old == null) sb.append("  + ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
			else if (!old.equals(e.getValue())) {
				sb.append("  ~ ").append(e.getKey()).append(' ').append(old).append(" -> ").append(e.getValue()).append('\n');
			}
		}
		return sb.toString();
	}

	/** 把当前 mod 集合记为"已知",并落盘 */
	public synchronized void update() {
		known.clear();
		known.putAll(currentMods());
		dirty = true;
		save();
	}

	public synchronized void save() {
		Path p = filePath();
		if (!dirty && Files.exists(p)) return;
		try {
			Files.createDirectories(p.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			root.add("mods", GSON.toJsonTree(known));
			Path tmp = p.resolveSibling("hanhua_known_mods.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			HanhuaClient.LOGGER.warn("[Hanhua] failed to save known-mod inventory to {}", p, e);
		}
	}

	/** 该 mod 在 jar 里提供 lang 资源的命名空间(通常 == modid;取不到时回退 modid) */
	public static Set<String> assetNamespacesOf(String modId) {
		Set<String> out = new LinkedHashSet<>();
		out.add(modId);
		try {
			FabricLoader.getInstance().getModContainer(modId).ifPresent(mod -> {
				for (Path root : mod.getRootPaths()) {
					Path assets = root.resolve("assets");
					if (!Files.isDirectory(assets)) continue;
					try (DirectoryStream<Path> ds = Files.newDirectoryStream(assets)) {
						for (Path ns : ds) {
							if (Files.isDirectory(ns)) out.add(ns.getFileName().toString());
						}
					} catch (IOException ignored) { }
				}
			});
		} catch (Throwable ignored) { }
		return out;
	}
}
