package com.hanhua;

import com.hanhua.config.HanhuaConfig;
import com.hanhua.config.HanhuaConfigScreen;
import com.hanhua.translate.TranslationManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;

public class HanhuaClient implements ClientModInitializer {
	public static final String MOD_ID = "hanhua";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		HanhuaConfig.load();
		com.hanhua.pack.LangPackWriter.noteStartupState();   // 记下"启动时资源包在不在", 用于区分"被删"和"被用户关掉"
		TranslationManager.INSTANCE.start();
		registerCommands();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> TranslationManager.INSTANCE.shutdown());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TranslationManager.INSTANCE.scanOnWorldJoinAsync());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TranslationManager.INSTANCE.resetWorldScanFlag());
		registerApiKeyPromptOnTitle();
		installTitleProgressOverlay();
		if ("1".equals(System.getenv("HANHUA_SELFTEST"))) {
			TranslationManager.INSTANCE.selftest();
			scheduleConfigUiCheck();
			scheduleJoinScanSelfTest();
		}
		LOGGER.info("[Hanhua] loaded on Minecraft {} / Fabric Loader {} (enabled={}, apiKey={})",
				mcVersion(),
				FabricLoader.getInstance().getModContainer("fabricloader")
						.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"),
				HanhuaConfig.INSTANCE.enabled,
				HanhuaConfig.INSTANCE.hasApiKey() ? "set" : "MISSING");
	}

	/** 当前 Minecraft 版本号(26.x 起为日期版本, 如 26.3) */
	private static String mcVersion() {
		try {
			return net.minecraft.SharedConstants.getCurrentVersion().name();
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/** 自检: 8 秒后真实构建 Cloth 设置界面并执行布局初始化(不切换窗口,避免打断用户), 全程留日志 */
	private void scheduleConfigUiCheck() {
		Thread t = new Thread(() -> {
			try { Thread.sleep(8000); } catch (InterruptedException ignored) { return; }
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				try {
					Screen s = HanhuaConfigScreen.build(null);
					int w = mc.getWindow().getGuiScaledWidth();
					int h = mc.getWindow().getGuiScaledHeight();
					s.init(w, h);
					s.resize(w, h);
					LOGGER.info("[Hanhua][ui-selftest] Cloth config screen built+init OK ({})", s.getClass().getSimpleName());
				} catch (Throwable e) {
					LOGGER.error("[Hanhua][ui-selftest] config screen FAILED", e);
				}
			});
		}, "hanhua-ui-selftest");
		t.setDaemon(true);
		t.start();
	}

	/** 自检: 10 秒后模拟"进入世界"触发自动预扫(无需真进世界) */
	private void scheduleJoinScanSelfTest() {
		Thread t = new Thread(() -> {
			try { Thread.sleep(10000); } catch (InterruptedException ignored) { return; }
			Minecraft.getInstance().execute(() -> {
				LOGGER.info("[Hanhua][join-selftest] simulating world join -> auto pre-scan");
				TranslationManager.INSTANCE.scanOnWorldJoinAsync();
			});
		}, "hanhua-join-selftest");
		t.setDaemon(true);
		t.start();
	}

	private static volatile boolean mainMenuHandled = false;
	private static volatile boolean titleProgressHookInstalled = false;

	/**
	 * 开始菜单(标题界面)左上角实时显示汉化进度百分数。
	 * 注意: fabric 的 per-screen 事件要在 Screen.init 之后注册, 所以用全局 AFTER_INIT 过滤 TitleScreen。
	 */
	private void installTitleProgressOverlay() {
		if (titleProgressHookInstalled) return;
		titleProgressHookInstalled = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof TitleScreen)) return;
			try {
				ScreenEvents.afterForeground(screen).register((s, extractor, mouseX, mouseY, tickDelta) -> {
					try {
						if (!HanhuaConfig.INSTANCE.enabled) return;
						extractor.text(client.font, TranslationManager.INSTANCE.titleProgressLine(), 6, 6, 0xFF55FF55, true);
					} catch (Throwable ignored) { }
				});
			} catch (Throwable ignored) { }
		});
	}

	/**
	 * 主菜单钩子(每次启动只跑一次):
	 * 1) 把已有译文写成"含语言的资源包": 内容没变就什么都不做;变了只写文件, **不重载资源**;
	 *    首次还会把资源包登记进 options.txt, 由下一次正常资源加载生效(避免进游戏被反复重载);
	 * 2) 扫一次(本地, 不花钱): 统计真实覆盖率 + 把"新增模组 ∪ 上次没译完的"键入队(见 TranslationManager);
	 * 3) 没有 API Key 时弹出一次 Key 输入界面(ESC=跳过), 其它时机一律不弹。
	 */
	private void registerApiKeyPromptOnTitle() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (mainMenuHandled) return;
			if (!(screen instanceof TitleScreen)) return;
			mainMenuHandled = true;
			// 1) 资源包持久化(不重载)。改成后台写盘: 全量落盘可能耗时数秒, 原来在主线程同步写会在进主菜单时卡一下
			if (HanhuaConfig.INSTANCE.resourcePack) {
				try {
					TranslationManager.INSTANCE.persistPackOnTitleScreenAsync();
				} catch (Throwable e) {
					LOGGER.warn("[COC DS] failed to persist language resource pack", e);
				}
			}
			// 2) 开始菜单扫一次(用户要求: 没 Key 或关了总开关就不扫)
			try {
				TranslationManager.INSTANCE.scanOnTitleScreenAsync();
			} catch (Throwable e) {
				LOGGER.warn("[COC DS] title-screen scan failed to start", e);
			}
			// 3) 无 Key 时的一次性引导
			if (HanhuaConfig.INSTANCE.hasApiKey()) return;
			client.execute(() -> {
				try {
					client.setScreenAndShow(new com.hanhua.config.ApiKeyScreen(null));
					LOGGER.info("[COC DS] main menu opened without API key -> input screen shown once (ESC to skip)");
				} catch (Throwable e) {
					LOGGER.error("[COC DS] failed to open API key input screen", e);
				}
			});
		});
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("hanhua")
						.executes(ctx -> status(ctx.getSource()))
						.then(ClientCommands.literal("status").executes(ctx -> status(ctx.getSource())))
						.then(ClientCommands.literal("reload").executes(ctx -> reload(ctx.getSource())))
						.then(ClientCommands.literal("clearcache")
								.executes(ctx -> clearCachePreview(ctx.getSource()))
								.then(ClientCommands.literal("confirm").executes(ctx -> clearCache(ctx.getSource()))))
						.then(ClientCommands.literal("pack").executes(ctx -> pack(ctx.getSource())))
						.then(ClientCommands.literal("key")
								.executes(ctx -> keyStatus(ctx.getSource()))
								.then(ClientCommands.argument("value", StringArgumentType.greedyString())
										.executes(ctx -> setKey(ctx.getSource(), StringArgumentType.getString(ctx, "value")))))
						.then(ClientCommands.literal("mods").executes(ctx -> mods(ctx.getSource())))
						.then(ClientCommands.literal("scan")
								.then(ClientCommands.argument("modid", StringArgumentType.word())
										.executes(ctx -> scan(ctx.getSource(), StringArgumentType.getString(ctx, "modid")))))
		));
	}

	private static int status(FabricClientCommandSource src) {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		TranslationManager m = TranslationManager.INSTANCE;
		src.sendFeedback(Component.literal("§e【COC的DS汉化】§f自动汉化状态:"));
		src.sendFeedback(Component.literal("  §b" + m.progressLine()));
		src.sendFeedback(Component.literal("  · 启用: " + (c.enabled ? "§a是" : "§c否")));
		src.sendFeedback(Component.literal("  · API Key: " + (c.hasApiKey() ? "§a已设置" : "§c未设置(不会翻译)")));
		String keyHint = m.apiKeyHintLine();
		if (!keyHint.isEmpty()) src.sendFeedback(Component.literal("  · " + keyHint));
		src.sendFeedback(Component.literal("  · 模型: " + c.model + " §7目标语言: " + c.targetLanguage));
		src.sendFeedback(Component.literal("  · 缓存条目: " + m.cacheSize() + " §7(上限 " + c.maxCacheEntries + ")"));
		src.sendFeedback(Component.literal("  · 队列: 待发 " + m.pendingSize() + " / 在途 " + m.inflightSize()
				+ " §7本次承诺要译 " + (m.workRemaining() + m.translatedTotal()) + " 条, 剩 " + m.workRemaining() + " 条"));
		String err = m.lastError();
		src.sendFeedback(Component.literal("  · 最近错误: "
				+ (err == null ? "§a无" : "§c" + err)));
		src.sendFeedback(Component.literal("  · 汉化资源包: "
				+ (TranslationManager.INSTANCE.packEnabled() ? "§a已启用(随游戏正常加载,不额外重载)"
						: TranslationManager.INSTANCE.packFilesReady() ? "§e已生成, 下次启动生效" : "§c未生成")
				+ " §7" + TranslationManager.INSTANCE.packPath()));
		src.sendFeedback(Component.literal("  · 已记录模组: " + m.knownModCount()
				+ " §7本次变化 " + m.newOrUpdatedMods().size() + " 个"
				+ (c.translateOnlyNewMods ? "(只翻新增模组)" : "(每次全量)")));
		src.sendFeedback(Component.literal("§7提示: /hanhua reload|clearcache|pack|mods|scan <modid|all> ; 设置: ModMenu → COC DS Sinicization"));
		return Command.SINGLE_SUCCESS;
	}

	/** /hanhua key <sk-...>: 命令行填 Key(设置界面打不进字时的兜底) */
	private static int setKey(FabricClientCommandSource src, String value) {
		String v = value == null ? "" : value.trim();
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (v.equalsIgnoreCase("clear") || v.equals("-")) {
			c.apiKey = "";
			c.save();
			TranslationManager.INSTANCE.clearApiKeyProblem();
			src.sendFeedback(Component.literal("§a【COC的DS汉化】已清空 API Key(不再翻译)"));
			return Command.SINGLE_SUCCESS;
		}
		if (v.length() < 12 || !v.startsWith("sk-")) {
			src.sendError(Component.literal("§c【COC的DS汉化】Key 看起来不对(应以 sk- 开头)。用法: /hanhua key sk-xxxx ; 清空: /hanhua key clear"));
			return 0;
		}
		c.apiKey = v;
		c.save();
		TranslationManager.INSTANCE.onApiKeySet();
		src.sendFeedback(Component.literal("§a【COC的DS汉化】API Key 已保存(尾号 " + v.substring(v.length() - 4) + ")，已开始翻译"));
		return Command.SINGLE_SUCCESS;
	}

	private static int keyStatus(FabricClientCommandSource src) {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		String state = c.hasApiKey()
				? "§a已设置(尾号 " + c.apiKey.substring(Math.max(0, c.apiKey.length() - 4)) + ")"
				: "§c未设置";
		src.sendFeedback(Component.literal("§e【COC的DS汉化】API Key: " + state + " §7填法: /hanhua key sk-xxxx"));
		return Command.SINGLE_SUCCESS;
	}

	/** 重写并立刻启用汉化资源包(手动: 会重载一次资源) */
	private static int pack(FabricClientCommandSource src) {
		TranslationManager m = TranslationManager.INSTANCE;
		int n = m.writePackNow();
		if (n <= 0 && !m.packFilesReady()) {
			src.sendFeedback(Component.literal("§e【COC的DS汉化】暂无译文可打包(缓存为空?)"));
			return Command.SINGLE_SUCCESS;
		}
		src.sendFeedback(Component.literal("§a【COC的DS汉化】资源包已重写("
				+ (n > 0 ? n + " 个命名空间" : "内容未变") + ") → " + m.packPath()));
		src.sendFeedback(Component.literal("§7正在重载资源使其立刻生效(仅此一次)..."));
		TranslationManager.INSTANCE.reloadPackNow();
		return Command.SINGLE_SUCCESS;
	}

	/** 显示模组清单对比结果 */
	private static int mods(FabricClientCommandSource src) {
		TranslationManager m = TranslationManager.INSTANCE;
		src.sendFeedback(Component.literal("§e【COC的DS汉化】已记录 " + m.knownModCount() + " 个模组; 本次新增/更新:"));
		String d = m.modChangeDescription();
		if (d == null || d.isBlank()) {
			src.sendFeedback(Component.literal("  §a(无变化 → 不会调用 API)"));
		} else {
			for (String line : d.split("\n")) {
				if (!line.isBlank()) src.sendFeedback(Component.literal("  §b" + line.trim()));
			}
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int reload(FabricClientCommandSource src) {
		HanhuaConfig.load();
		src.sendFeedback(Component.literal("§a【COC的DS汉化】配置已重新加载"));
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * `/hanhua clearcache`(不带参数): 只预览会删什么, 不动手 —— 破坏性操作必须二次确认。
	 * 0.5.5 实测坑: 用户敲错一次, 304 条译文+222 条"保持原文"判定+资源包全没, 扫描立刻多出 163 条"未译"。
	 */
	private static int clearCachePreview(FabricClientCommandSource src) {
		TranslationManager m = TranslationManager.INSTANCE;
		src.sendFeedback(Component.literal("§e【COC的DS汉化】clearcache 将删除以下内容(删除后只能靠重新翻译/社区包恢复):"));
		src.sendFeedback(Component.literal("  §7· 译文缓存 §f" + m.cacheSize() + " 条§7(hanhua_translations.json)"));
		src.sendFeedback(Component.literal("  §7· 汉化资源包 §f"
				+ (m.packEnabled() ? "已启用" : m.packFilesReady() ? "已生成待生效" : "未生成")
				+ "§7(删除后下次翻译会重建)"));
		src.sendFeedback(Component.literal("  §7· 本次翻译进度计数(已译/在册/跳过名单)"));
		src.sendFeedback(Component.literal("  §a不会删: 「保持原文」判定 " + m.keptOriginalKeys()
				+ " 条(模型已确认这些键本来就该是英文, 重新判定无意义)"));
		src.sendFeedback(Component.literal("§e确认删除请再敲: §f/hanhua clearcache confirm"));
		return Command.SINGLE_SUCCESS;
	}

	private static int clearCache(FabricClientCommandSource src) {
		TranslationManager.INSTANCE.clearCache();
		src.sendFeedback(Component.literal("§a【COC的DS汉化】译文缓存已清空 + 资源包已删除(下次翻译会重建)"));
		src.sendFeedback(Component.literal("§7「保持原文」判定记录已保留(模型已确认这些键本来就该是英文, 重新判定无意义)"));
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * `/hanhua scan <modid|all>`: 全部投递到后台执行后立刻返回。
	 * 扫描要读资源管理器并逐命名空间解析 lang 文件, 在命令线程(渲染线程)上同步跑会卡帧;
	 * all 走 requestScanAllAsync(已含 scanRunning 闸), 单命名空间走 requestScanNamespaceAsync。
	 * 结果在完成后由后台线程回主线程用聊天栏回报。
	 */
	private static int scan(FabricClientCommandSource src, String modid) {
		TranslationManager m = TranslationManager.INSTANCE;
		if ("all".equalsIgnoreCase(modid)) {
			m.requestScanAllAsync();
			src.sendFeedback(Component.literal("§a【COC的DS汉化】已开始全量扫描(后台执行, 完成后陆续自动翻译...)"));
		} else {
			m.requestScanNamespaceAsync(modid);
		}
		return Command.SINGLE_SUCCESS;
	}
}
