package com.hanhua.translate;

import com.hanhua.HanhuaClient;
import com.hanhua.config.HanhuaConfig;
import com.hanhua.pack.LangPackWriter;
import com.hanhua.pack.ModInventory;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 翻译调度中心(单例):
 * - resolve(): 渲染线程安全查询;命中缓存返回译文,未命中入队异步请求(本帧仍显示原文,下一帧生效)
 * - 后台按 batchSize 合并键位 → DeepSeekApi 批量翻译 → 校验占位符 → 写缓存(3s 防抖落盘)
 * - 进度口径(0.4.7 重做): "缺中文" = **客户端语言表里的实际生效值仍是英文**(键存在但值是英文原文也算缺);
 *   旧口径只看"某个 zh_cn.json 里有没有这个键", 于是模组自带 zh_cn 里那些"值还是英文"的键被算成已有中文,
 *   进度 100% 而菜单仍是英文(用户实测反馈)。现在按玩家真正看到的文字判定, 该翻的键一个都跑不掉;
 * - 只扫两次(用户要求): 打开游戏的开始菜单一次 + 进入世界一次; 关了总开关不扫;
 *   没填 Key 也扫 —— 只统计缺键 + 吸收社区包现成译文(不登记不入队), 填 Key 后再扫一次补齐登记;
 * - 失败处理: 429/超时等按轮次退避重试(最多 {@link #MAX_RETRY_ROUNDS} 轮, 3s/8s/20s), 仍失败记"跳过"并计入完成;
 *   401/402/403/404 属于配置问题 → **暂停**请求(不再白试), 用户改好 Key 后自动继续。
 * - 0.4.8 稳定性(用户报"启动崩溃、日志被 hanhua-dispatcher 刷屏"): 进度日志只在数字真的变化时输出,
 *   空闲时一行都不打; 语言查询注入点加了保险丝({@link HookGuard}, 出错即熔断, 绝不把异常抛给游戏);
 *   后台线程不再读写 {@code mc.options} 这类客户端主线程对象 —— 相关判断全部 {@code mc.execute} 回主线程再做。
 */
public final class TranslationManager {
	public static final TranslationManager INSTANCE = new TranslationManager();
	/** 单个键自动重试的最大轮数, 超过就记"跳过"(保证进度能诚实走到 100%) */
	private static final int MAX_RETRY_ROUNDS = 3;
	/** 重试退避: 第 1/2/3 轮分别等 3s / 8s / 20s(0.4.7 从 5/20/60 再压短 —— 单条键最坏只卡 31 秒, 用户反馈"卡 99%"太久) */
	private static final long[] RETRY_DELAYS_MS = { 3_000L, 8_000L, 20_000L };
	private static final int MAX_PENDING = 8000;
	/** 并发硬上限(用户要求"尽量把所有键一次发完": 待译多就自动多开请求) */
	private static final int MAX_PARALLEL = 24;
	/** 单个请求里"键+原文"的字符预算: 太大回答会被截断, 太小则请求数变多(约等于输出 4k token) */
	private static final int REQUEST_CHAR_BUDGET = 12_000;
	/** 被限流(429)后多久之内只用用户配置的并发数 */
	private static final long RATE_LIMIT_COOLDOWN_MS = 60_000L;
	/** 回答被截断/无法解析后, 这段时间内改用小批量重试 */
	private static final long SMALL_BATCH_WINDOW_MS = 120_000L;
	private static final int SMALL_BATCH_KEYS = 40;
	private static final int SMALL_BATCH_CHARS = 3_500;
	/** zh_cn 至少应读到这么多键, 否则认为"资源还没加载好", 不当成缺失去入队 */
	private static final int MIN_SANE_ZH_KEYS = 1000;
	private static final int SMALL_ZH_RETRIES = 3;
	/** 原版自己的命名空间: "模组中文覆盖率"不算它们(原版 zh_cn 基数太大, 会把百分比撑到 93%+) */
	private static final Set<String> VANILLA_NAMESPACES = Set.of("minecraft", "realms");

	private final TranslationCache cache = TranslationCache.load();
	/** 社区包直接命中(吸收为译文, 未调 API)的累计条数 —— 省钱效果可见 */
	final java.util.concurrent.atomic.AtomicLong communityHits = new java.util.concurrent.atomic.AtomicLong();
	private final DeepSeekApi api = new DeepSeekApi();
	private final ModInventory inventory = ModInventory.load();
	/** 模型判定"不需要翻译、保持原文"的键(持久化; 不再重试、不再排队) */
	private final KeepOriginalStore keepOriginal = KeepOriginalStore.load();
	/** 正在跑的请求数(注意: 不是"在途键数", 一个请求可以带几百条键) */
	private final java.util.concurrent.atomic.AtomicInteger inflightRequests = new java.util.concurrent.atomic.AtomicInteger();
	/** 最近一次被限流的时间(429) 与 最近一次回答被截断的时间 */
	private volatile long lastRateLimitAt = 0;
	private volatile long smallBatchUntil = 0;
	/** 资源包"还在不在"的巡检节流 */
	private volatile long lastPackCheckAt = 0;
	private final ConcurrentHashMap<String, String> pending = new ConcurrentHashMap<>();
	/** 键 -> 下次可以重试的时间戳(指数退避); 到点前不会被再次取走 */
	private final ConcurrentHashMap<String, Long> retryAt = new ConcurrentHashMap<>();
	/** 键 -> 已经自动重试过几轮(成功即清零; 超过 MAX_RETRY_ROUNDS 记"跳过") */
	private final ConcurrentHashMap<String, Integer> retryRounds = new ConcurrentHashMap<>();
	/** 模型把原文原样退回的次数(只给"看起来该翻译"的文本一次严格重问的机会) */
	private final ConcurrentHashMap<String, Integer> keepStrikes = new ConcurrentHashMap<>();
	/** 下次要用"必须翻译"的严格提示词重问的键 */
	private final Set<String> strictKeys = ConcurrentHashMap.newKeySet();
	private final Set<String> inflight = ConcurrentHashMap.newKeySet();
	private final AtomicLong translatedTotal = new AtomicLong();
	private final AtomicBoolean saveScheduled = new AtomicBoolean();
	private final AtomicBoolean packWriteScheduled = new AtomicBoolean();
	/** 资源包写出正在执行中(writeAll 全量落盘期间) —— 与 packWriteScheduled 一起挡住"写出没完就重载"的竞态 */
	private final AtomicBoolean packWriteInFlight = new AtomicBoolean();
	/** 自上次重载后资源包是否写进了新内容 */
	private final AtomicBoolean packWriteHappened = new AtomicBoolean();
	/** 资源包重载是否正在进行(避免并发重载) */
	private final AtomicBoolean reloadInProgress = new AtomicBoolean();
	/** 上一次重载资源包的时间(两次重载至少隔 5 秒, 避免频繁卡顿) */
	private volatile long lastPackReloadAt = 0;
	private final AtomicBoolean reloadBlockLogged = new AtomicBoolean();
	/** 用户手动关掉资源包时只提示一次(否则 flush 每 250ms 都重试一次并打一行日志) */
	private final AtomicBoolean packDisabledLogged = new AtomicBoolean();
	/** 最近一次翻译/写包活动时间(用于"队列安静 2 秒"判断) */
	private volatile long lastActivityAt = 0;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean worldScanDone = new AtomicBoolean(false);
	private final AtomicBoolean scanRunning = new AtomicBoolean(false);

	private ScheduledExecutorService scheduler;
	private java.util.concurrent.ExecutorService workers;
	private volatile String lastError = null;
	private volatile long lastErrorAt = 0;
	/**
	 * API Key 被服务端判定不可用时的提示(401 无效 / 402 余额不足 / 403 无权限), null = 无问题。
	 * 设置界面在「DeepSeek API Key」那一栏正下方渲染这一行;没有问题时返回空串,
	 * Cloth 的 TextListEntry 在文本为空时高度为 0, 所以界面上不会留空行(用户要求)。
	 */
	private volatile String apiKeyProblem = null;

	// ---- 汉化进度统计(供设置界面/命令显示) ----
	/** 扫描过的命名空间数 */
	private final java.util.concurrent.atomic.AtomicInteger scanNamespaces = new java.util.concurrent.atomic.AtomicInteger();
	/** 范围内英文键总数(原始口径, 仅展示) */
	private final java.util.concurrent.atomic.AtomicLong scanEnTotal = new java.util.concurrent.atomic.AtomicLong();
	/** 扫描时"已经有中文"的键数(所有命名空间 zh_cn 并集 + 本地缓存) */
	private final java.util.concurrent.atomic.AtomicLong scanZhTotal = new java.util.concurrent.atomic.AtomicLong();
	/** 扫描时"确实没有中文、需要翻译"的键数(分母的增量部分, 跨会话稳定) */
	private final java.util.concurrent.atomic.AtomicLong scanMissingTotal = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong scanQueuedTotal = new java.util.concurrent.atomic.AtomicLong();
	/** 规则上不可翻译的键(不参与进度分母) */
	private final java.util.concurrent.atomic.AtomicLong scanInvalidTotal = new java.util.concurrent.atomic.AtomicLong();
	/** 模组命名空间(不含原版 minecraft/realms)的 en 键数与已有中文键数 —— 明细里的覆盖率参考 */
	private final java.util.concurrent.atomic.AtomicLong scanModEnTotal = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong scanModZhTotal = new java.util.concurrent.atomic.AtomicLong();
	/** 扫描时被社区包吸收(现成译文, 未调 API)的键数 —— 无 Key 口径的进度分子之一 */
	private final AtomicLong scanCommunityAbsorbed = new AtomicLong();
	/** 完全没有语言文件、资源包改不到的模组名(明细里如实列出) */
	private volatile java.util.List<String> untranslatableMods = java.util.List.of();
	/** 本次要译的活的"已完成"数量(累加: 跨扫描不清零, 跨会话持久化) */
	private final java.util.concurrent.atomic.AtomicInteger workDoneCount = new java.util.concurrent.atomic.AtomicInteger();
	/** 本次要译的活的"总数"(累加; 只有新登记的工作才会让它变大) */
	private final java.util.concurrent.atomic.AtomicInteger workTotalCount = new java.util.concurrent.atomic.AtomicInteger();
	/** 登记过、但还没有结果的键(进度里"还差多少"就是它); 译好/跳过时移除 */
	private final Set<String> workKeys = ConcurrentHashMap.newKeySet();
	/** 运行中(渲染时)碰到的待译键: 立即打包翻译, 但**不算进进度百分比**, 只在明细里单独显示 */
	private final Set<String> runtimeKeys = ConcurrentHashMap.newKeySet();
	private final java.util.concurrent.atomic.AtomicInteger runtimeSeen = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger runtimeDone = new java.util.concurrent.atomic.AtomicInteger();
	/** 本会话是否已经对齐过持久化的进度计数(第一次扫描时对齐一次, 之后不再动) */
	private volatile boolean workCountersAligned = false;
	/** 本次会话承诺要翻的键(新增模组 ∪ 上次没译完的): 用来判"干完了没"、重试与重载资源包 */
	private final Set<String> committedKeys = ConcurrentHashMap.newKeySet();
	/** 承诺键的英文原文(队列满/中途丢失时用来补队, 保证"开始译了就译完") */
	private final ConcurrentHashMap<String, String> committedText = new ConcurrentHashMap<>();
	private volatile long lastTopUpAt = 0;
	/** 本次会话"干完了"之后是否已经落过盘(避免反复写文件) */
	private volatile boolean persistedDone = false;
	/**
	 * 进度日志(0.4.8 重写): 只在**数字真的变了**的时候输出, 干完/空闲时只报一次"没活了", 之后彻底安静。
	 * 旧实现是"百分比没变也每 10 秒打一行"的心跳 —— 挂机几小时日志里就是几千行一模一样的
	 * "进度 100% (已译 1075/1075 ...)", 启动器做崩溃分析时会拿日志末尾当线索, 满屏的 hanhua-dispatcher
	 * 会把汉化模组误报成崩溃嫌疑人(用户实测反馈)。
	 */
	private volatile long lastProgressLogAt = 0;
	private volatile String lastProgressSignature = "";
	private volatile int lastProgressPct = -2;
	private volatile boolean idleLogged = false;
	/** 本次会话重试用尽、已放弃的键(自动扫描不再动它们; 手动扫描会清空重来) */
	private final Set<String> skippedKeys = ConcurrentHashMap.newKeySet();
	/** 跳过后被重新尝试的键: 重新 commitKey 时要把 giveUp 记过的旧账撤掉, 防 done/total 虚增 */
	private final Set<String> regivenKeys = ConcurrentHashMap.newKeySet();
	/** 承诺过的命名空间(持久化, 供下次会话续译; 用户要求"开始译了就译完") */
	private final Set<String> committedNamespaces = ConcurrentHashMap.newKeySet();
	/** 上次会话留下的、还没译完的命名空间 */
	private final Set<String> pendingScopeNamespaces = ConcurrentHashMap.newKeySet();
	private volatile long lastScanAt = 0;
	private volatile String lastScanScope = null;
	/** 本会话是否已经真正扫描过(填 Key 后要判断需不需要立刻补扫) */
	private volatile boolean scannedThisSession = false;
	/** zh_cn 读太少的重试计数 */
	private volatile int zhRetryCount = 0;

	private TranslationManager() { }

	public void start() {
		if (!started.compareAndSet(false, true)) return;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "hanhua-dispatcher");
			t.setDaemon(true);
			return t;
		});
		workers = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "hanhua-worker");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::flush, 500, 250, TimeUnit.MILLISECONDS);
		Runtime.getRuntime().addShutdownHook(new Thread(cache::save, "hanhua-cache-save"));
		restorePersistedProgress();
		Glossary.load();
		CommunityPackManager.INSTANCE.setReadyListener(this::onCommunityPackReady);
		CommunityPackManager.INSTANCE.startAsync(com.hanhua.config.HanhuaConfig.INSTANCE);
		HanhuaClient.LOGGER.info("[Hanhua] TranslationManager started ({} cached entries)", cache.size());
	}

	/**
	 * 社区汉化包到达终态(本地包就绪/下载完成/失败/禁用)时回调(0.5.3):
	 * 无 Key 时扫描只统计了缺键没登记, 此时社区表已从空变全 -> 补扫一轮把现成译文吸收进来。
	 * 有 Key / 还没扫过 / 无缺键时不动作(扫描时已经查过社区表, 或没什么可吸收)。
	 */
	private void onCommunityPackReady() {
		try {
			if (HanhuaConfig.INSTANCE.hasApiKey()) return;
			if (!scannedThisSession) return;               // 扫描还没跑 -> 扫描自己会查到新表, 不用补
			if (scanMissingTotal.get() <= 0) return;
			scheduler.execute(() -> {
				try {
					if (HanhuaConfig.INSTANCE.hasApiKey()) return;
					if (scanRunning.get()) return;
					HanhuaClient.LOGGER.info("[Hanhua] 社区汉化包就绪 -> 补扫一轮, 吸收现成译文(不调 API)");
					scanAutomatic("社区包就绪补扫");
				} catch (Throwable t) {
					HanhuaClient.LOGGER.debug("[Hanhua] community-ready rescan failed: {}", t.toString());
				}
			});
		} catch (Throwable ignored) { }
	}

	/** 用上次会话的统计初始化进度, 这样开始菜单立刻能显示百分数(本次扫描完成后会被替换) */
	private void restorePersistedProgress() {
		try {
			ProgressStore.Snapshot s = ProgressStore.load();
			if (s == null) return;
			scanNamespaces.set((int) s.namespaces);
			scanEnTotal.set(s.enKeys);
			scanZhTotal.set(s.zhCovered);
			scanMissingTotal.set(s.missing);
			scanQueuedTotal.set(s.queued);
			workDoneCount.set((int) s.workDone);
			workTotalCount.set((int) Math.max(s.workTotal, s.workDone));
			// 恢复未完成键集合 —— 不恢复的话 workComplete() 启动即误判"干完",
			// flush() 会在下次扫描前用空 scope 覆盖快照(强杀场景续译承诺静默丢失); 计数对齐也会虚算成 100%
			if (s.unfinishedKeys != null && !s.unfinishedKeys.isEmpty()) {
				// 恢复即对账: 缓存里已有译文的键 = 强杀前其实已完成(API 响应已收、缓存已写, 只是没来得及记账)。
				// 这类键直接记为完成 —— 留着就是"幽灵账": 它们下次扫描因"已有中文"不再被登记,
				// committedText 永远等不到原文, 补不了队, 进度会永久卡住(真机实测卡 91%)。
				int recovered = 0;
				for (String k : s.unfinishedKeys) {
					if (cache.get(k) != null) {
						recovered++;
					} else {
						workKeys.add(k);
						committedKeys.add(k);
					}
				}
				if (recovered > 0) {
					workDoneCount.addAndGet(recovered);
					HanhuaClient.LOGGER.info("[Hanhua] 恢复对账: 快照里 {} 条键缓存已有译文(强杀前的在途工作), 直接记为完成", recovered);
				}
			}
			workCountersAligned = false;         // 第一次扫描时按实际缺键对齐(见 scanInternal)
			scanModEnTotal.set(s.modEn);
			scanModZhTotal.set(s.modZh);
			scanUnitsTotal.set((int) s.namespaces);
			scanUnitsDone.set((int) s.namespaces);
			lastScanAt = s.at;
			lastScanScope = "(上次会话统计)";
			if (ProgressStore.hasUnfinished(s)) {
				// 上次没译完 -> 记下承诺过的那批命名空间, 本次扫描继续译完(用户要求"开始译了就译完")
				pendingScopeNamespaces.clear();
				if (s.scope != null) pendingScopeNamespaces.addAll(s.scope);
				HanhuaClient.LOGGER.info("[Hanhua] 上次还没译完: 待续译命名空间 {} 个", pendingScopeNamespaces.size());
			}
			HanhuaClient.LOGGER.info("[Hanhua] restored progress snapshot: 本次要译 {}/{} ({}%), 未译 {}, 跳过 {}",
					workDoneCount.get(), workTotalCount.get(), progressPercent(),
					Math.max(0, s.missing - s.workDone), s.skipped);
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] progress restore failed: {}", t.toString());
		}
	}

	private void persistProgress() {
		boolean incomplete = !committedKeys.isEmpty();
		ProgressStore.save(scanNamespaces.get(), scanEnTotal.get(), scanZhTotal.get(), scanMissingTotal.get(),
				workTotalCount.get(), workDoneCount.get(), skippedKeys.size(), pending.size() + inflight.size(),
				scanModEnTotal.get(), scanModZhTotal.get(),
				incomplete ? committedNamespaces : java.util.Set.of(), workKeys, incomplete);
	}

	public void shutdown() {
		if (started.compareAndSet(true, false)) {
			scheduler.shutdownNow();
			workers.shutdownNow();
		}
		cache.save();
		// 0.4.8: 打一条"正常退出"标记 —— 日志里有它 = 游戏是走正常关闭流程退出的;
		// 没有它而日志又断了 = 进程被外部强杀(任务管理器/启动器强制结束/崩溃), 便于用户分辨"到底是谁的问题"
		HanhuaClient.LOGGER.info("[Hanhua] 正常退出: 游戏走的是正常关闭流程(本次会话累计翻译 {} 条, 缓存 {} 条)",
				translatedTotal.get(), cache.size());
	}

	/** 当前是否处于"应翻译"状态: 开关+Key+游戏语言=目标语言 */
	public boolean active() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.enabled || !c.hasApiKey()) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getLanguageManager() == null) return false;
		String sel = mc.getLanguageManager().getSelected();
		return sel == null || c.targetLanguage == null || c.targetLanguage.isBlank() || c.targetLanguage.equals(sel);
	}

	/** 是否"可读译文": 开关+语言匹配(不要求 Key) —— 无 Key 时缓存/社区包译文照样生效, 只是不入队不请求 API */
	public boolean readable() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.enabled) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getLanguageManager() == null) return false;
		String sel = mc.getLanguageManager().getSelected();
		return sel == null || c.targetLanguage == null || c.targetLanguage.isBlank() || c.targetLanguage.equals(sel);
	}

	/** 渲染线程入口: 返回缓存译文或 null(未命中已入队) */
	public String resolve(String key, String english) {
		if (!readable()) return null;
		if (!KeyRules.looksTranslatable(key, english)) return null;
		// 社区包覆盖模式(默认关): 开启后社区译文优先于本地缓存
		if (com.hanhua.config.HanhuaConfig.INSTANCE.communityOverrideCache) {
			String cov = CommunityPackManager.INSTANCE.lookup(key);
			if (cov != null) {
				adoptCommunity(key, english, cov);
				return cov;
			}
		}
		String hit = cache.get(key);
		if (hit != null) return hit;
		// 社区包兜底(P3): 缓存没有 -> 社区包有现成译文 -> 直接吸收, 不调 API
		String community = CommunityPackManager.INSTANCE.lookup(key);
		if (community != null) {
			adoptCommunity(key, english, community);
			return community;
		}
		if (!active()) return null;      // 无 Key: 只读(缓存/社区包), 不入队不请求 API
		enqueue(key, english);
		return null;
	}

	/** 社区包命中(P3): 吸收为正式译文 —— 记进度 + 写缓存 + 标写包, 不调 API(省 tokens 的核心路径) */
	private void adoptCommunity(String key, String english, String zh) {
		commitKey(key, english);
		cache.putAll(Map.of(key, zh));
		// 可能正躺在 API 队列/退避里等着: 社区已有现成译文 -> 摘干净, 省一次请求
		pending.remove(key);
		retryAt.remove(key);
		retryRounds.remove(key);
		if (workKeys.remove(key)) workDoneCount.incrementAndGet();
		// 社区译文同样要落进资源包。这里**不能**直接置 packWriteHappened —— 那只是"要重载"的信号,
		// 若没有真的写盘, 就会重载出旧包(用户实测的"一直在重载资源包")。排期写盘后由 writePackNow
		// 负责置位, 并同时刷新 lastActivityAt(写盘完成前重载判定会被 packWrite 标志挡住)。
		schedulePackWrite();
	}

	private void enqueue(String key, String english) {
		if (!HanhuaConfig.INSTANCE.hasApiKey()) return;   // 无 Key 硬闸: 任何路径都不许入队
		if (!canQueue(key)) return;
		// 运行中碰到的键: 立即打包发出去(用户要求"检测到多少就发多少"), 但**不计入进度百分比** ——
		// 否则每渲染到一条英文键百分比就 100→99→100 抖一下(用户实测反馈)。
		runtimeKeys.add(key);
		runtimeSeen.incrementAndGet();
		pending.put(key, english);
	}

	/** 队列准入: 退避中/在途/已排队/队伍满/已判过"保持原文"/session 内已放弃 都不再收 */
	private boolean canQueue(String key) {
		long now = System.currentTimeMillis();
		Long until = retryAt.get(key);
		if (until != null && now < until) return false;
		if (skippedKeys.contains(key)) return false;
		// 模型已经判过"这条本来就该是英文" -> 别再每帧重复花钱问一遍(渲染线程会不停调用 resolve)
		if (keepOriginal.contains(key)) return false;
		if (inflight.contains(key) || pending.containsKey(key)) return false;
		return pending.size() < MAX_PENDING;
	}

	/** 记为"本次要译的活": workKeys 负责进度记账(累加, 不清零), committedKeys 负责"必须译完" */
	private void commitKey(String key, String english) {
		// 曾经 giveUp 过的键被重新尝试: 先撤掉旧账(giveUp 记了 done+1 且已出册, 重新登记会 total+1),
		// 不撤的话每重试一轮 done/total 各虚增 1, "本次 430/470"这类数字会失真
		if (regivenKeys.remove(key)) {
			workDoneCount.decrementAndGet();
			workTotalCount.decrementAndGet();
		}
		if (workKeys.add(key)) workTotalCount.incrementAndGet();
		committedKeys.add(key);
		if (english != null) committedText.put(key, english);
	}

	private void flush() {
		// 注意: 定时任务里抛异常会导致 scheduleWithFixedDelay 永久停摆, 所以整体兜底
		try {
			maybeReloadPackOnComplete();
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] reload-on-complete check failed", t);
		}
		try {
			topUpQueue();
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] queue top-up failed", t);
		}
		try {
			sweepStalledKeys();
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] stalled-key sweep failed", t);
		}
		try {
			if (!workComplete()) {
				persistedDone = false;
			} else if (!persistedDone && pending.isEmpty() && inflight.isEmpty()) {
				persistedDone = true;      // 干完了: 落一次盘(下次启动直接显示真实进度), 只写一次
				persistProgress();
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] persist-after-complete failed: {}", t.toString());
		}
		try {
			maybeRebuildMissingPack();
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] pack self-heal check failed", t);
		}
		try {
			flushPending();
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] flush failed", t);
		}
		try {
			maybeLogProgress();
		} catch (Throwable ignored) { }
	}

	/** 死账自愈(0.4.8): workKeys 里的键补不了队且队列安静超过这个毫秒数 -> 记为跳过 */
	private static final long STALLED_KEY_GRACE_MS = 30_000L;
	private volatile long stalledSince = 0;

	/**
	 * 死账自愈保险丝: workKeys 里的键若"没有原文可补队、也不在退避"且队列持续安静 30 秒,
	 * 说明它成了幽灵账(典型: 恢复的未完成键后来在语言表里已有中文, 扫描按实际缺中文的口径
	 * 不再登记 -> committedText 永远等不到原文 -> 没人处理, 进度永久卡住)。直接记为跳过,
	 * 让进度能诚实走完; 手动扫描仍会清空跳过名单给它们再一次机会。
	 * 首次扫描对齐前不启用 —— 那时键还没被登记, 会误伤刚恢复的键。
	 */
	private void sweepStalledKeys() {
		if (workKeys.isEmpty() || !workCountersAligned || !pending.isEmpty() || !inflight.isEmpty()) {
			stalledSince = 0;
			return;
		}
		boolean hasStalled = false;
		for (String k : workKeys) {
			if (committedText.containsKey(k)) continue;   // 有原文, topUpQueue 会补队
			if (retryAt.containsKey(k)) continue;         // 退避重试中, 轮次用尽前还有机会
			hasStalled = true;
			break;
		}
		if (!hasStalled) { stalledSince = 0; return; }
		long now = System.currentTimeMillis();
		if (stalledSince == 0) { stalledSince = now; return; }
		if (now - stalledSince < STALLED_KEY_GRACE_MS) return;
		for (String k : new java.util.ArrayList<>(workKeys)) {
			if (committedText.containsKey(k) || retryAt.containsKey(k)) continue;
			HanhuaClient.LOGGER.info("[Hanhua] 死账自愈: '{}' 无原文可补队且队列安静 30 秒 -> 计入完成"
					+ "(不进跳过名单, 下次扫描仍会重新登记)", k);
			writeOffGhost(k);
		}
		stalledSince = 0;
	}

	/**
	 * 进度日志(0.4.8 重写): **数字没变就一个字都不打**。
	 *
	 * <p>规则:
	 * <ul>
	 *   <li>还在干活(有在册/排队/在途/退避重试) → 百分比一变立刻报, 否则最多 5 秒一行;</li>
	 *   <li>没活了 → 只报一次"本次没有可做的事了", 之后完全安静(挂机多久都不会再刷屏)。</li>
	 * </ul>
	 */
	private void maybeLogProgress() {
		if (!scanKnown()) return;
		long now = System.currentTimeMillis();
		int pct = progressPercent();
		int done = workDone();
		int total = workTotal();
		int left = (int) remainingKeys();
		int queued = pending.size();
		int running = inflight.size();
		int retrying = retryPendingCount();
		boolean busy = left > 0 || queued > 0 || running > 0 || retrying > 0;
		String sig = pct + "|" + done + "|" + total + "|" + left + "|" + queued + "|" + running + "|" + retrying;
		if (sig.equals(lastProgressSignature)) return;         // 数字没变 -> 不输出
		if (busy) {
			boolean pctChanged = pct != lastProgressPct;
			if (!pctChanged && now - lastProgressLogAt < 5_000L) return;   // 干活期间最多 5 秒一行
			lastProgressLogAt = now;
			lastProgressSignature = sig;
			lastProgressPct = pct;
			idleLogged = false;
			HanhuaClient.LOGGER.info("[Hanhua] 进度 {}% (已译 {}/{}, 未译 {}, 队列 {} + {}{})",
					pct, done, total, left, queued, running,
					retrying > 0 ? ", 退避重试中 " + retrying : "");
			return;
		}
		lastProgressSignature = sig;
		lastProgressPct = pct;
		if (idleLogged) return;
		idleLogged = true;
		HanhuaClient.LOGGER.info("[Hanhua] 进度 {}% (已译 {}/{}) —— 队列已空, 本次没有可做的事了(之后不再重复输出进度)",
				pct, done, total);
	}

	/**
	 * 汉化资源包被删/丢了 -> 立刻用缓存重建, 并重新登记进 options.txt。
	 * 用户实测: 删掉资源包后重译, 进度卡着、包也没恢复 —— 一半原因是重建后没被重新启用。
	 */
	private void maybeRebuildMissingPack() {
		if (!HanhuaConfig.INSTANCE.resourcePack) return;
		if (cache.size() <= 0) return;
		long now = System.currentTimeMillis();
		if (now - lastPackCheckAt < 10_000L) return;
		lastPackCheckAt = now;
		if (!LangPackWriter.missingNow()) return;
		HanhuaClient.LOGGER.info("[Hanhua] 汉化资源包不在了 -> 用 {} 条缓存立刻重建", cache.size());
		writePackNow();
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) mc.execute(LangPackWriter::autoEnableWithoutReload);
	}

	/**
	 * 补队: 承诺过的键如果不在队列里(队列满被挡掉/中途丢了), 这里重新放回队列,
	 * 保证"扫描完一定自动译到 100%"。退避中的键不碰。
	 */
	private void topUpQueue() {
		if (committedKeys.isEmpty() || pending.size() >= MAX_PENDING) return;
		long now = System.currentTimeMillis();
		if (now - lastTopUpAt < 2000L) return;
		lastTopUpAt = now;
		for (String k : committedKeys) {
			if (pending.size() >= MAX_PENDING) return;
			if (pending.containsKey(k) || inflight.contains(k)) continue;
			Long until = retryAt.get(k);
			if (until != null && now < until) continue;
			String en = committedText.get(k);
			if (en == null) {
				// 理论上不会发生(登记承诺时都会带上原文); 真发生了也不能让"承诺"永远清不掉
				HanhuaClient.LOGGER.debug("[Hanhua] committed key without source text, dropped: {}", k);
				committedKeys.remove(k);
				continue;
			}
			pending.put(k, en);
		}
	}

	private void flushPending() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!started.get() || !c.enabled || !c.hasApiKey() || pending.isEmpty()) return;
		// Key/地址有问题(401/402/403/404): 暂停请求, 不再白试; 用户在设置里改好后 clearApiKeyProblem() 自动继续
		if (apiKeyProblem != null) return;
		// 一口气把并发槽填满: 待译多就多发几个请求(每个请求尽量装最多的键)
		int guard = 0;
		while (guard++ < MAX_PARALLEL) {
			if (inflightRequests.get() >= effectiveConcurrency(c, pending.size())) return;
			Map<String, String> take = takeBatch(c);
			if (take.isEmpty()) return;
			inflightRequests.incrementAndGet();
			final HanhuaConfig cfg = c;
			workers.execute(() -> {
				try {
					doTranslate(take, cfg);
				} finally {
					inflightRequests.decrementAndGet();
				}
			});
		}
	}

	/**
	 * 实际并发数: 用户配置的值是**下限**, 待译多时自动加大(上限 {@link #MAX_PARALLEL}),
	 * 被限流(429)之后 60 秒内退回到用户配置的值。
	 */
	private int effectiveConcurrency(HanhuaConfig c, int queued) {
		int configured = Math.max(1, Math.min(MAX_PARALLEL, c.maxConcurrentRequests));
		int perBatch = Math.max(1, Math.min(400, c.batchSize));
		int needed = (queued + perBatch - 1) / perBatch;
		int effective = Math.max(configured, needed);
		if (System.currentTimeMillis() - lastRateLimitAt < RATE_LIMIT_COOLDOWN_MS) {
			effective = configured;                      // 刚被限流过 -> 老实一点
		}
		return Math.max(1, Math.min(MAX_PARALLEL, effective));
	}

	/** 取一批键: 受"键数上限"和"字符预算"双重限制(字符预算保证回答不会被截断) */
	private Map<String, String> takeBatch(HanhuaConfig c) {
		boolean small = System.currentTimeMillis() < smallBatchUntil;
		int maxKeys = small ? SMALL_BATCH_KEYS : Math.max(1, Math.min(400, c.batchSize));
		int budget = small ? SMALL_BATCH_CHARS : REQUEST_CHAR_BUDGET;
		long now = System.currentTimeMillis();
		Map<String, String> take = new HashMap<>();
		int chars = 0;
		Iterator<Map.Entry<String, String>> it = pending.entrySet().iterator();
		while (it.hasNext() && take.size() < maxKeys) {
			Map.Entry<String, String> e = it.next();
			if (inflight.contains(e.getKey())) continue;        // 正在请求中(刚放回队列的)
			Long until = retryAt.get(e.getKey());
			if (until != null && now < until) continue;         // 退避中, 留在队列里等下一轮
			int cost = e.getKey().length() + (e.getValue() == null ? 0 : e.getValue().length());
			if (!take.isEmpty() && chars + cost > budget) break;  // 预算满了 -> 下一批
			it.remove();
			take.put(e.getKey(), e.getValue());
			inflight.add(e.getKey());
			chars += cost;
		}
		return take;
	}

	private void doTranslate(Map<String, String> take, HanhuaConfig cfg) {
		boolean strict = !strictKeys.isEmpty() && take.keySet().stream().anyMatch(strictKeys::contains);
		try {
			Map<String, String> res = api.translate(take, cfg, strict);
			if (res.isEmpty()) throw new IOException("empty/unparseable response");
			Map<String, String> store = new HashMap<>();       // 可用译文
			Map<String, String> retry = new HashMap<>();       // 模型没返回 / 占位符坏了 -> 重试
			Map<String, String> second = new HashMap<>();      // 模型原样退回, 但值得用严格提示再问一次
			java.util.List<String> keepSrc = new java.util.ArrayList<>();  // 模型说"保持原文" -> 终局, 不重试
			for (Map.Entry<String, String> kv : take.entrySet()) {
				String t = res.get(kv.getKey());
				String src = kv.getValue();
				if (t == null || t.isBlank()) {
					retry.put(kv.getKey(), src);               // 模型漏了这条 -> 值得重试
				} else if (t.equals(src) || !KeyRules.containsCjk(t)) {
					// 模型说"这条不用翻"(或给了个没有中文的结果)
					int strike = keepStrikes.merge(kv.getKey(), 1, Integer::sum);
					if (!strict && strike <= 1 && needsSecondChance(src)) {
						second.put(kv.getKey(), src);          // 0.4.7: 普通英文词被原样退回 -> 严格再问一次
					} else {
						keepSrc.add(kv.getKey());              // "Shift"/"Discord"/模组名: 本来就该是英文
					}
				} else if (KeyRules.tokensPreserved(src, t)) {
					store.put(kv.getKey(), t);
				} else {
					retry.put(kv.getKey(), src);               // 占位符被破坏 -> 重试
				}
			}
			if (!keepSrc.isEmpty()) {
				for (String k : keepSrc) {
					keepOriginal.add(k);
					strictKeys.remove(k);
					onKeyFinished(k, true);                    // 算完成, 不再排队、不再重试
				}
				keepOriginal.save();
				HanhuaClient.LOGGER.info("[Hanhua] {} 条键模型判定无需翻译(保持原文, 已记入 {})",
						keepSrc.size(), KeepOriginalStore.filePath().getFileName());
			}
			if (!store.isEmpty()) {
				cache.putAll(store);
				translatedTotal.addAndGet(store.size());
				int max = cfg.maxCacheEntries;
				if (max > 0 && cache.size() > max * 2L) cache.trim(max);
				scheduleSave();
				schedulePackWrite();
			}
			lastError = null;
			apiKeyProblem = null;          // 这次真的翻译成功了: 旧的"Key 无效"提示自动消失
			lastActivityAt = System.currentTimeMillis();
			for (String k : store.keySet()) onKeyFinished(k, true);
			// 原样退回的普通英文词 -> 换成"必须翻译"的严格提示立刻再问一次(不算失败轮数)
			if (!second.isEmpty()) {
				long wait = System.currentTimeMillis() + 800L;
				for (Map.Entry<String, String> e : second.entrySet()) {
					strictKeys.add(e.getKey());
					retryAt.put(e.getKey(), wait);
					pending.put(e.getKey(), e.getValue());
				}
				HanhuaClient.LOGGER.info("[Hanhua] {} 条键模型原样退回 -> 用「必须翻译」的严格提示再问一次", second.size());
			}
			// 200 但个别键没译出来/译得不合格 -> 按失败处理(退避重试, 超过轮数记"跳过")
			if (!retry.isEmpty()) {
				onBatchFailure(retry, "模型没返回该键/占位符不符");
			}
		} catch (DeepSeekApi.ApiException e) {
			lastError = "HTTP " + e.status + " " + brief(e.getMessage());
			lastErrorAt = System.currentTimeMillis();
			String problem = apiKeyProblemFor(e.status);
			if (problem != null) {
				// Key 层面的问题: 记下来给设置界面提示(在 API Key 那一行下面显示)
				apiKeyProblem = problem;
			}
			if (e.status == 401 || e.status == 403 || e.status == 402 || e.status == 404) {
				// 配置问题(Key 无效/欠费/地址错): 不惩罚键, 原样放回队列并暂停请求, 等用户改好后自动继续
				for (Map.Entry<String, String> kv : take.entrySet()) {
					retryAt.remove(kv.getKey());
					pending.put(kv.getKey(), kv.getValue());
				}
				HanhuaClient.LOGGER.warn("[Hanhua] 配置问题(HTTP {}) -> 暂停翻译, 修好后自动继续: {}", e.status, lastError);
			} else {
				if (e.status == 429) {
					lastRateLimitAt = System.currentTimeMillis();
					HanhuaClient.LOGGER.warn("[Hanhua] 被限流(429): {} 条键退避重试, 并发暂时压回配置值", take.size());
				} else {
					HanhuaClient.LOGGER.warn("[Hanhua] 批次失败 HTTP {} ({} 条键): {}",
							e.status, take.size(), brief(e.getMessage()));
				}
				onBatchFailure(take, "HTTP " + e.status);
			}
		} catch (Exception e) {
			lastError = brief(String.valueOf(e.getMessage()));
			lastErrorAt = System.currentTimeMillis();
			String msg = String.valueOf(e.getMessage());
			if (msg.contains("unparseable") || msg.contains("truncated")) {
				// 回答被截断/解析不了: 多半是这批太大 -> 用小批量立刻重试(首次缩批不算失败轮数)。
				// 但如果**上次就已经缩到小批量**还是解析不了, 那就不是"批太大"的问题了, 必须按真失败
				// 计轮退避, 否则这批键会被无限原样放回队列, 进度永远差一点到不了 100%(且持续花 API)。
				long nowMs = System.currentTimeMillis();
				boolean alreadySmall = nowMs < smallBatchUntil;   // 必须先读后写(顺序不能反, 否则恒为 false)
				smallBatchUntil = nowMs + SMALL_BATCH_WINDOW_MS;
				HanhuaClient.LOGGER.warn("[Hanhua] 回答无法解析({} 条键{}) -> 接下来 {} 秒改用小批量重试",
						take.size(), alreadySmall ? ", 小批量下仍失败" : "", SMALL_BATCH_WINDOW_MS / 1000);
				if (take.size() == 1 || alreadySmall) {
					onBatchFailure(take, alreadySmall ? "小批量下回答仍无法解析" : "单条回答无法解析");
				} else {
					for (Map.Entry<String, String> kv : take.entrySet()) {
						retryAt.remove(kv.getKey());
						pending.put(kv.getKey(), kv.getValue());
					}
				}
			} else {
				HanhuaClient.LOGGER.warn("[Hanhua] 批次失败({} 条键): {}", take.size(), lastError);
				onBatchFailure(take, e.getClass().getSimpleName());
			}
		} finally {
			inflight.removeAll(take.keySet());
		}
	}

	/** 401/402/403 才算"Key 的问题", 返回给用户看的提示文案; 其它状态码返回 null */
	private static String apiKeyProblemFor(int status) {
		switch (status) {
			case 401:
				return "§c⚠ API Key 无效(DeepSeek 返回 401)：Key 填错或已失效，请重新填写后点『完成并退出』保存";
			case 402:
				return "§c⚠ API Key 余额不足(DeepSeek 返回 402)：请到 platform.deepseek.com 充值或更换 Key";
			case 403:
				return "§c⚠ API Key 被拒绝(DeepSeek 返回 403)：该 Key 没有访问权限，请检查 Key 或模型名";
			case 404:
				return "§c⚠ 接口地址错误(DeepSeek 返回 404)：请检查设置里的『API 地址』是否为 https://api.deepseek.com (程序会自动补 /chat/completions)";
			default:
				return null;
		}
	}

	/**
	 * 一批键失败了: 每个键记一轮, 退避 {@link #backoffFor(int)} 后重试;
	 * 超过 {@link #MAX_RETRY_ROUNDS} 轮 -> 记"跳过"(计入已完成, 进度不会被永远卡住)。
	 */
	private void onBatchFailure(Map<String, String> take, String reason) {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, String> e : take.entrySet()) {
			String key = e.getKey();
			int round = retryRounds.merge(key, 1, Integer::sum);
			if (round > MAX_RETRY_ROUNDS) {
				HanhuaClient.LOGGER.warn("[Hanhua] 放弃翻译 '{}' (失败 {} 轮, {}) -> 计入跳过", key, round - 1, reason);
				giveUp(key);
			} else {
				retryAt.put(key, now + backoffFor(round));
				pending.put(key, e.getValue());
			}
		}
	}

	/** 第 n 轮退避: 5s, 20s, 60s(上限 60s, 只对真正失败的键生效) */
	private static long backoffFor(int round) {
		int idx = Math.max(0, Math.min(RETRY_DELAYS_MS.length - 1, round - 1));
		return RETRY_DELAYS_MS[idx];
	}

	/** 某个键有结果了(译好): 清退避与承诺, 并推进进度分子 */
	private void onKeyFinished(String key, boolean translated) {
		retryRounds.remove(key);
		retryAt.remove(key);
		keepStrikes.remove(key);
		strictKeys.remove(key);
		committedKeys.remove(key);
		committedText.remove(key);
		if (runtimeKeys.remove(key)) runtimeDone.incrementAndGet();  // 运行中补译的 -> 不动进度百分比
		if (workKeys.remove(key)) workDoneCount.incrementAndGet();   // 进度分子 +1(累加, 只增不减)
	}

	/** 重试轮数用完 -> 记"跳过"(进度里按已完成计, 界面会显示"跳过 N 条") */
	private void giveUp(String key) {
		retryRounds.remove(key);
		retryAt.remove(key);
		keepStrikes.remove(key);
		strictKeys.remove(key);
		committedKeys.remove(key);
		committedText.remove(key);
		pending.remove(key);
		skippedKeys.add(key);
		regivenKeys.add(key);
		if (runtimeKeys.remove(key)) runtimeDone.incrementAndGet();
		if (workKeys.remove(key)) workDoneCount.incrementAndGet();
	}

	/**
	 * 核销"无原文可补队"的幽灵账(快照恢复的未完成键, 却没能被扫描补上原文)。
	 *
	 * <p>与 giveUp 一样: 清干净账目并计入完成; 区别是**不加入 skippedKeys** —— 它并不是"翻译失败 3 轮",
	 * 不该享受封禁待遇, 否则后续自动扫描会因 {@code !skippedKeys.contains(key)} 拒绝重新登记它。
	 * regivenKeys 必须加: 将来若被重新登记, 由 commitKey 撤掉这次记的账, 保证 total/done 不虚增。
	 */
	private void writeOffGhost(String key) {
		retryRounds.remove(key);
		retryAt.remove(key);
		keepStrikes.remove(key);
		strictKeys.remove(key);
		committedKeys.remove(key);
		committedText.remove(key);
		pending.remove(key);
		regivenKeys.add(key);
		if (runtimeKeys.remove(key)) runtimeDone.incrementAndGet();
		if (workKeys.remove(key)) workDoneCount.incrementAndGet();
	}

	/**
	 * 模型把原文原样退回时: 这条文本值不值得用"必须翻译"的严格提示再问一次?
	 * 只对"去掉占位符/颜色代码后还剩真正英文单词"的短文本这么做 —— 品牌名再问一次也还是原样退回,
	 * 代价只是一个小请求; 而 'Inventory'、'Light Updates Warning' 这类普通词第一次被退回就再也译不出来了(用户实测)。
	 */
	static boolean needsSecondChance(String src) {
		if (src == null) return false;
		String s = src.trim();
		if (s.length() < 4 || s.length() > 120) return false;   // 太短/太长都不折腾(长文本失败会走正常重试)
		if (KeyRules.containsCjk(s)) return false;
		if (s.contains("://")) return false;                    // 网址原样保留
		return KeyRules.looksLikeTranslatableProse(s);
	}

	/** 原版自己的命名空间不算进"模组中文覆盖率" */
	private static boolean isModNamespace(String namespace) {
		return namespace != null && !VANILLA_NAMESPACES.contains(namespace);
	}


	private void scheduleSave() {
		if (!saveScheduled.compareAndSet(false, true)) return;
		scheduler.schedule(() -> {
			saveScheduled.set(false);
			cache.save();
		}, 3, TimeUnit.SECONDS);
	}

	private static String brief(String s) {
		if (s == null) return "unknown";
		return s.length() > 200 ? s.substring(0, 200) : s;
	}

	// ---- 资源包持久化(含语言的资源包): 把译文写成 resourcepacks/<pack>/assets/<ns>/lang/zh_cn.json ----

	/** 5 秒防抖后把最新缓存写入资源包(后台线程) */
	private void schedulePackWrite() {
		if (!HanhuaConfig.INSTANCE.resourcePack) return;
		if (!packWriteScheduled.compareAndSet(false, true)) return;
		scheduler.schedule(() -> {
			try {
				writePackNow();                 // 内部自己置/清 packWriteInFlight
			} finally {
				// 写完之后才清"已排期": 与 packWriteInFlight 首尾相扣, 中间不留空窗
				// (否则慢写落盘的几秒里会被重载判定当成"没人在写", 重载出半成品包)
				packWriteScheduled.set(false);
			}
		}, 2, TimeUnit.SECONDS);          // 2s 防抖(原来 5s): 让"译完 -> 重载"更快
	}

	/** 立即把缓存落成资源包(内容没变则一个字节都不写),返回命名空间数 */
	public int writePackNow() {
		// 标志所有权放在这里: 所有调用方(防抖任务 / 标题界面 / 自愈重建 / `/hanhua pack`)都受保护,
		// 写盘期间重载判定一律不动手
		packWriteInFlight.set(true);
		try {
			int n = LangPackWriter.writeAll(cache.snapshot(), HanhuaConfig.INSTANCE.targetLanguage);
			if (n != LangPackWriter.UNCHANGED) lastActivityAt = System.currentTimeMillis();
			if (n > 0) {
				packWriteHappened.set(true);
				HanhuaClient.LOGGER.info("[Hanhua] language resource pack updated: {} namespaces, {} keys -> {}",
						n, LangPackWriter.lastKeyCount(), LangPackWriter.packDir());
			} else if (n == LangPackWriter.UNCHANGED) {
				HanhuaClient.LOGGER.info("[Hanhua] language resource pack unchanged ({} keys), skipped writing",
						LangPackWriter.lastKeyCount());
			}
			return n;
		} finally {
			packWriteInFlight.set(false);
		}
	}

	/**
	 * 把资源包登记进 options.txt 的 resourcePacks(不重载资源!)。
	 * 必须在客户端主线程调用;真正加载发生在下一次正常的资源加载。
	 */
	public boolean registerPackInOptions() {
		return LangPackWriter.autoEnableWithoutReload();
	}

	/** 手动路径: 立刻重载一次让资源包生效(仅 /hanhua pack 使用) */
	public void reloadPackNow() {
		// 手动重载意味着"已经立刻生效了": 清掉待重载信号 + 刷新节流时间。
		// 否则 writePackNow 置下的 packWriteHappened 会让 flush 在 1.5~5 秒后再自动重载一次(用户实测双闪)。
		packWriteHappened.set(false);
		lastPackReloadAt = System.currentTimeMillis();
		reloadBlockLogged.set(false);
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		mc.execute(LangPackWriter::enableAndReloadNow);
	}

	/**
	 * 开始菜单用: 在后台线程把缓存落成资源包, 写完回主线程登记进 options.txt(不直接重载)。
	 *
	 * <p>原来这一步在 TitleScreen 的 AFTER_INIT 回调里同步做, 数千键 / 十几个命名空间的全量落盘
	 * 可能耗时数秒, 会阻塞渲染线程(进主菜单明显卡一下)。writeAll 本身线程安全, 挪到后台即可。
	 */
	public void persistPackOnTitleScreenAsync() {
		if (!HanhuaConfig.INSTANCE.resourcePack || !started.get()) return;
		scheduler.execute(() -> {
			try {
				int n = writePackNow();
				if (n > 0 || packFilesReady()) {
					Minecraft mc = Minecraft.getInstance();
					if (mc != null) mc.execute(this::registerPackInOptions);
				}
			} catch (Throwable t) {
				HanhuaClient.LOGGER.warn("[Hanhua] title-screen pack persist failed", t);
			}
		});
	}

	public boolean packEnabled() {
		return LangPackWriter.isEnabled();
	}

	public boolean packFilesReady() {
		return LangPackWriter.hasPackFiles();
	}

	public String packPath() {
		return LangPackWriter.packDir().toString();
	}

	// ---- 新增模组检测: 只有 mod 集合发生变化时才值得花钱调 API ----
	public java.util.Set<String> newOrUpdatedMods() {
		return inventory.newOrUpdated();
	}

	public String modChangeDescription() {
		return inventory.describeChanges();
	}

	public int knownModCount() {
		return inventory.knownCount();
	}

	// ---- 汉化进度(设置界面 / 命令共用) ----
	// 口径(0.4.3, 按用户要求"显示进度要和实际翻译进度一致"):
	//  百分比 = 本次要译的活的完成度 = 已译 / (已译 + 还没结果的键)。
	//  · 扫描一完成就是 0%, 一直涨到 100%; 它**不是**"模组里本来有多少中文"(那个底子有 93%, 用户明确指出不合理);
	//  · 分子分母都是**累加**的: 进世界那次重扫不会清零重算(修掉"76% 打回 0%"), 重启后从上次的位置继续;
	//  · 只有"新来的活"(新增模组/上次没译完的)才会让分母变大、百分比相应回落, 那是真实新增的工作量;
	//  · 模组文本本来的中文覆盖率只在明细里当参考数字显示。

	/** 本次要译的键总数(累加: 曾经登记过的活, 含已译/跳过/在途) */
	public int workTotal() {
		return workTotalCount.get();
	}

	/** 本次要译的键里已经有结果的(译好 + 跳过) */
	public int workDone() {
		return workDoneCount.get();
	}

	/** 还没出结果的键数(队列/在途/退避中) */
	public int workRemaining() {
		return workKeys.size();
	}

	/** 该翻的活是否都干完了(老模组里从没译过的键不在范围内, 那是省钱的预期行为) */
	public boolean workComplete() {
		return workKeys.isEmpty();
	}

	/** "已完成"的键数(扫描时已有中文 + 本次已译 + 已跳过) —— 明细里的参考数字 */
	public long translatedKeys() {
		long missing = scanMissingTotal.get();
		long done = Math.min(missing, workDoneCount.get());
		return scanZhTotal.get() + done;
	}

	/** 覆盖口径的分母: 需要中文的键总数(已有中文 + 需要翻译的) */
	public long coveredOrMissingKeys() {
		return scanZhTotal.get() + scanMissingTotal.get();
	}

	/**
	 * 覆盖口径的分子: 现在已经"有中文/已被处理"的键数
	 * = 扫描时已有中文 + min(缺中文, 社区包吸收 + 本次已干的活)。
	 * 有没有 Key 都用这一个口径 —— 主数字永远只有一种含义, 不再双口径跳变。
	 */
	public long coveredKeys() {
		long missing = scanMissingTotal.get();
		long resolved = scanCommunityAbsorbed.get() + workDoneCount.get();
		return scanZhTotal.get() + Math.min(missing, resolved);
	}

	/** 扫描时"缺中文、需要翻译"的键数 */
	public long missingKeys() {
		return scanMissingTotal.get();
	}

	/** 这些键里还没出结果的(队列/在途/退避中的都算) —— 注意不能用"累计已译"去减, 那样会算出负数 */
	public long remainingKeys() {
		return workKeys.size();
	}

	/** 模型判定"保持原文"的键数(Shift/Discord/模组名这类, 不算失败也不算跳过) */
	public long keptOriginalKeys() {
		return keepOriginal.size();
	}

	/** 重试用尽被放弃的键数(界面上要如实写出来) */
	public long skippedKeys() {
		return skippedKeys.size();
	}

	/** 规则上不可翻译、不参与进度的键数(符号/纯数字之类) */
	public long invalidKeys() {
		return scanInvalidTotal.get();
	}

	/** 模组命名空间的英文键总数(明细里的覆盖率参考) */
	public long modEnKeys() { return scanModEnTotal.get(); }

	/** 其中已经有中文的键数(明细里的覆盖率参考) */
	public long modZhCoveredKeys() { return scanModZhTotal.get(); }

	/**
	 * 主进度百分比 0-100 = 中文覆盖率: coveredKeys() / coveredOrMissingKeys()。
	 * 全场景唯一口径(有没有 Key 都是这一个数): 无 Key 时随社区包吸收增长, 有 Key 时随 AI 补译增长,
	 * 只反映"现在有多少键有中文", 不再出现"设置页 50% / 主界面 100%"的双口径跳变(0.5.4 用户实测反馈)。
	 * -1 = 还没扫描过(且没有上次会话的统计)。
	 */
	public int progressPercent() {
		long denom = scanZhTotal.get() + scanMissingTotal.get();
		if (denom <= 0) return scanKnown() ? 100 : -1;
		return (int) Math.max(0, Math.min(100, coveredKeys() * 100L / denom));
	}

	/**
	 * 当前正在干什么(状态机一行), 是主数字的唯一搭档 —— 数字说"到哪了", 这行说"在干啥/下一步":
	 * 统计中… / 社区包下载中… / AI 补译中 x/y / 还差 N 条填 Key / 全部完成 / Key 有问题已暂停。
	 */
	public String activityLine() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.enabled) return "已停用";
		if (apiKeyProblem()) return "§cAPI Key/地址有问题, 已暂停 —— 改好后自动继续";
		if (scanUnitsRunning) return "扫描统计中… " + scanUnitsDone.get() + "/" + scanUnitsTotal.get();
		if (c.worldLoadScan && !scannedThisSession) return "等待自动扫描…";
		long need = scanMissingTotal.get() - Math.min(scanMissingTotal.get(),
				scanCommunityAbsorbed.get() + workDoneCount.get());
		if (!c.hasApiKey()) {
			if (c.communityEnabled && !CommunityPackManager.INSTANCE.isSettled()) return "社区汉化包下载中…";
			if (need > 0) return "还差 " + need + " 条未汉化 —— 填 API Key 后由 AI 补译";
			return c.communityEnabled ? "社区汉化包已加载, 已全部覆盖" : "已全部覆盖";
		}
		int remain = workKeys.size();
		if (remain > 0) {
			return "AI 补译中 " + workDoneCount.get() + "/" + workTotalCount.get() + "(还剩 " + remain + " 条)";
		}
		if (skippedKeys.size() > 0) return "全部完成(跳过 " + skippedKeys.size() + " 条失败)";
		return "全部完成";
	}

	public long scanEnKeys() { return scanEnTotal.get(); }
	public long scanZhCoveredKeys() { return scanZhTotal.get(); }
	public long scanQueuedKeys() { return scanQueuedTotal.get(); }
	public long lastScanAt() { return lastScanAt; }
	public String lastScanScope() { return lastScanScope; }
	public boolean scanKnown() { return lastScanAt > 0; }

	/** 一行实时进度(设置界面左上角 / 状态命令), 控制长度避免被窗口裁掉 */
	public String progressLine() {
		int pct = progressPercent();
		int filled = pct < 0 ? 0 : Math.round(pct / 10f);
		StringBuilder bar = new StringBuilder();
		for (int i = 0; i < 10; i++) bar.append(i < filled ? '█' : '░');
		StringBuilder sb = new StringBuilder();
		sb.append("汉化进度 [").append(bar).append("] ");
		if (pct < 0) {
			sb.append("待扫描");
		} else {
			sb.append(pct).append('%');
		}
		// 主数字 = 覆盖率(唯一口径); 后面跟"当前在干啥/下一步"状态机一行, 不再堆第二套数字让用户自己换算
		sb.append("  ").append(activityLine());
		int retrying = retryPendingCount();
		if (retrying > 0) sb.append("  重试中 ").append(retrying);
		sb.append("  队列 ").append(pending.size()).append('+').append(inflight.size());
		sb.append(packEnabled() ? "  资源包:已启用" : packFilesReady() ? "  资源包:待启动生效" : "  资源包:未生成");
		return sb.toString();
	}

	/**
	 * 开始菜单左上角的极简进度: "汉化进度 97% · AI 补译中 227/446(还剩 219 条)"。
	 * 扫描进行期间主数字会重新定基(计数清零重攒), 此时不显示数字免得看着乱跳。
	 */
	public String titleProgressLine() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		// 扫描没跑完(或本次会话还没扫) -> 不显示数字(恢复快照会给个旧百分数, 用户要求"统计出多少未汉化后再显示真实进度")
		if (c.enabled && (scanUnitsRunning || (c.worldLoadScan && !scannedThisSession))) return "汉化进度 统计中…";
		int pct = progressPercent();
		if (pct < 0) return "汉化进度 --";
		return "汉化进度 " + pct + "% · " + activityLine();
	}

	// ---- 扫描进度(已扫描模组/命名空间 数) ----
	private final java.util.concurrent.atomic.AtomicInteger scanUnitsTotal = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger scanUnitsDone = new java.util.concurrent.atomic.AtomicInteger();
	private volatile boolean scanUnitsRunning = false;
	private volatile long lastScanRequestAt = 0;

	public boolean scanInProgress() { return scanUnitsRunning; }
	public int scanUnitsTotal() { return scanUnitsTotal.get(); }
	public int scanUnitsDone() { return scanUnitsDone.get(); }

	/** 扫描进度百分比; -1 = 还没扫过 */
	public int scanPercent() {
		int total = scanUnitsTotal.get();
		if (total <= 0) return scanKnown() ? 100 : -1;
		return Math.min(100, scanUnitsDone.get() * 100 / total);
	}

	/** 界面上显示的扫描进度文案 */
	public String scanProgressLine() {
		int total = scanUnitsTotal.get();
		int done = scanUnitsDone.get();
		int pct = scanPercent();
		if (scanUnitsRunning) {
			return "扫描进度 " + Math.max(1, pct) + "% (" + done + "/" + total + " 个模组)";
		}
		if (total <= 0) return scanKnown() ? "扫描进度 100%" : "扫描进度 --  (点这里开始扫描)";
		return (pct >= 100 ? "扫描完成 " : "扫描进度 ") + pct + "% (" + done + "/" + total + " 个模组)";
	}

	/** 手动全量扫描(设置页按钮): 后台线程执行, 3 秒内只受理一次点击 */
	public void requestScanAllAsync() {
		long now = System.currentTimeMillis();
		if (scanUnitsRunning || scanRunning.get() || now - lastScanRequestAt < 3000L) {
			HanhuaClient.LOGGER.debug("[Hanhua] scan request ignored (running={}, last={}ms ago)",
					scanUnitsRunning, now - lastScanRequestAt);
			return;
		}
		lastScanRequestAt = now;
		scheduler.execute(() -> {
			try {
				int queued = scanAll();
				HanhuaClient.LOGGER.info("[Hanhua] 手动扫描完成: 入队 {} 个键", queued);
			} catch (Throwable t) {
				HanhuaClient.LOGGER.warn("[Hanhua] manual scan failed", t);
			}
		});
	}

	/**
	 * 命令用: 后台扫描单个命名空间, 完成后回主线程用聊天栏回报结果。
	 * 扫描要读资源管理器 + 逐命名空间解析 lang 文件, 放在命令线程(渲染线程)上会卡帧, 所以投递到 dispatcher。
	 */
	public void requestScanNamespaceAsync(String namespace) {
		if (!started.get() || namespace == null || namespace.isBlank()) return;
		scheduler.execute(() -> {
			int q;
			try {
				q = scanNamespace(namespace);
			} catch (Throwable t) {
				HanhuaClient.LOGGER.warn("[Hanhua] scan '{}' failed", namespace, t);
				q = -1;
			}
			final int fq = q;
			Minecraft mc = Minecraft.getInstance();
			if (mc == null) return;
			mc.execute(() -> {
				if (mc.player == null) return;
				String msg = fq < 0 ? "§c扫描失败: 请确认已启用、已填 API Key, 且资源已加载(进过世界)"
						: fq == 0 ? "§e命名空间 " + namespace + ": 没有需要翻译的键(已覆盖/已缓存), 或已有扫描在进行"
						: "§a命名空间 " + namespace + ": 已入队 " + fq + " 个待翻译键, 后台自动翻译中";
				mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("【COC的DS汉化】" + msg));
			});
		});
	}

	/** 进度是否已经跑完(扫描过 + 本次该翻的活干完 + 队列清空) */
	public boolean progressComplete() {
		return scanKnown() && workComplete() && pending.isEmpty() && inflight.isEmpty();
	}

	/**
	 * 把写好的资源包重载一次, 让新译文立刻生效(整个会话可以多次, 每次至少间隔 5 秒)。
	 *
	 * <p>重载只发生在"汉化工作结束"时(0.5.3 用户明确要求):
	 * <ul>
	 *   <li><b>有 Key</b>: 严格等 100% —— 本次登记的所有键都有结果(译好/跳过)才重载。
	 *       撤销 0.4.7"队列安静即可"的宽松化: 安静但还有键在退避重试中不算结束。
	 *       代价: 个别失败键最晚让重载延迟约 31 秒(3s/8s/20s 三轮退避用完记跳过);</li>
	 *   <li><b>无 Key</b>: 无法进行下一项(不能请求 API), 社区汉化包到达终态(本地包就绪/下载完成/失败/禁用)
	 *       即视为结束 —— 吸收的现成译文写盘后重载一次。</li>
	 * </ul>
	 * 两路共同前提: 本次会话扫描完成 + 有新译文写进包 + 写盘不在途 + 队列安静 1.5 秒 + 距上次重载 ≥5 秒。
	 */
	public void maybeReloadPackOnComplete() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.resourcePack || !c.reloadPackWhenComplete) return;
		if (!scannedThisSession) return;                   // 必须等本次会话扫描完(恢复快照不算)
		if (!packWriteHappened.get()) return;              // 本次启动没有新译文写进包 -> 不用重载
		if (reloadInProgress.get()) return;
		// 资源包写出已排期(2s 防抖中)/正在落盘 -> 等写完再判, 否则会出现"重载了旧包 -> 写完又置位 -> 5 秒后再重载"
		// 的连续双闪(用户实测进世界时连着重载两次)。写完后 flush 下拍自然会走到这里。
		if (packWriteScheduled.get() || packWriteInFlight.get()) return;
		if (c.hasApiKey()) {
			// 有 Key: 严格 100% —— 在册键全部有结果才算"汉化结束"
			if (!workComplete()) {
				if (reloadBlockLogged.compareAndSet(false, true)) {
					HanhuaClient.LOGGER.info("[Hanhua] 进度 {}%(在册剩 {}) -> 等全部译完再重载资源包",
							progressPercent(), workKeys.size());
				}
				return;
			}
		} else {
			// 无 Key: 社区包到终态(就绪/失败/禁用)即视为"汉化结束"
			if (!CommunityPackManager.INSTANCE.isSettled()) return;
		}
		long now = System.currentTimeMillis();
		if (!inflight.isEmpty() || hasReadyPending()) {
			if (reloadBlockLogged.compareAndSet(false, true)) {
				HanhuaClient.LOGGER.info("[Hanhua] 进度 {}%(在册剩 {} / 队列 {} + {}) -> 等队列安静后再重载资源包",
						progressPercent(), workKeys.size(), pending.size(), inflight.size());
			}
			return;
		}
		// 队列安静 1.5 秒再动手, 避免翻译刚写完就又重载
		if (now - lastActivityAt < 1500L) return;
		if (now - lastPackReloadAt < 5000L) return;        // 两次重载至少隔 5 秒(防抖, 避免频繁重载造成卡顿)
		if (!reloadInProgress.compareAndSet(false, true)) return;
		lastPackReloadAt = now;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) { reloadInProgress.set(false); return; }
		int retrying = retryPendingCount();
		// 0.4.8: 资源包的启用状态 / options.txt / 资源重载全都只能在渲染线程碰 —— 后台线程只负责判"该动手了",
		// 真正的判断和执行都放进 mc.execute(不然就是在后台线程读写 mc.options.resourcePacks, 属于数据竞争)
		mc.execute(() -> {
			try {
				if (!LangPackWriter.isEnabled()) {
					// 资源包是本次会话中途才生成的(启动时还不存在, 所以没进 options) -> 先登记; 用户手动关过则尊重用户
					if (!LangPackWriter.autoEnableWithoutReload()) {
						// 登记失败(文件缺失 / 用户手动关过 / mc 未就绪): 停掉"待重载"状态, 否则 flush 每 250ms
						// 都会再进这里重试一次(autoEnable 内部还会读一次 PackState 并打一行日志)
						packWriteHappened.set(false);
						if (LangPackWriter.hasPackFiles() && packDisabledLogged.compareAndSet(false, true)) {
							HanhuaClient.LOGGER.info("[Hanhua] 资源包未被启用(用户手动关闭过) -> 本次会话不再自动重载; "
									+ "想立刻生效可在「选项→资源包」里重新勾选它");
						}
						return;
					}
					// 登记成功: 保留 packWriteHappened —— 下一拍 isEnabled() 已为真, 走正常重载让包立刻生效
					HanhuaClient.LOGGER.info("[Hanhua] 资源包已登记进 options(下一拍重载判定会让它立刻生效)");
					return;
				}
				boolean ok = LangPackWriter.enableAndReloadNow();
				packWriteHappened.set(false);             // 新内容已经生效
				reloadBlockLogged.set(false);
				if (workComplete()) persistProgress();    // 全干完了: 把进度快照落盘
				HanhuaClient.LOGGER.info("[Hanhua] 队列已安静(进度 {}%, 退避重试中 {} 条) -> 重载资源包一次 (ok={})",
						progressPercent(), retrying, ok);
			} catch (Throwable t) {
				HanhuaClient.LOGGER.warn("[Hanhua] reload on completion failed", t);
			} finally {
				reloadInProgress.set(false);
			}
		});
	}

	/** 队列里还有"现在就能发出去"的键吗(退避中的不算, 它们要等时间到) */
	private boolean hasReadyPending() {
		long now = System.currentTimeMillis();
		for (String k : pending.keySet()) {
			Long until = retryAt.get(k);
			if (until == null || now >= until) return true;
		}
		return false;
	}

	/** 正在退避重试中的键数(进度行/明细里如实显示, 免得看着像卡住) */
	public int retryPendingCount() {
		long now = System.currentTimeMillis();
		int n = 0;
		for (String k : pending.keySet()) {
			Long until = retryAt.get(k);
			if (until != null && now < until) n++;
		}
		return n;
	}

	/** 设置界面里的明细行(整份快照, 供命令/日志用) */
	public java.util.List<String> progressDetails() {
		java.util.List<String> out = new java.util.ArrayList<>();
		int pct = progressPercent();
		if (pct < 0) {
			out.add("总体进度: 待扫描统计 (本地已有译文 " + cache.size() + " 条)");
		} else {
			// 主数字 = 中文覆盖率(唯一口径, 与开始菜单左上角/进度条是同一个数)
			out.add("总体进度: " + pct + "% —— 中文覆盖率(已覆盖 " + coveredKeys()
					+ " / 共 " + coveredOrMissingKeys() + " 条需要中文的键)");
		}
		out.add("当前状态: " + activityLine());
		if (scanKnown()) {
			out.add("参考: 本次扫描的 " + scanEnKeys() + " 个英文键里, 扫描时已有中文 "
					+ scanZhCoveredKeys() + " 个(按客户端实际生效值判定, 不含原版自带的中文)");
			out.add("本次扫描发现 " + missingKeys() + " 条没中文的键 —— 社区包吸收 " + scanCommunityAbsorbed.get()
					+ " 条, 本次已处理 " + Math.min(missingKeys(), workDone()) + " 条"
					+ "(待发 " + pending.size() + " / 在途 " + inflight.size()
					+ " / 排队中的请求 " + inflightRequests.get() + ")");
		}
		int retrying = retryPendingCount();
		if (retrying > 0) {
			out.add("重试中 " + retrying + " 条(失败自动重试, 退避 3s/8s/20s; 连续 " + MAX_RETRY_ROUNDS + " 轮失败会记入「已跳过」)");
		}
		if (keptOriginalKeys() > 0) {
			out.add("保持原文 " + keptOriginalKeys() + " 条(模型判定无需翻译, 如 Shift/Discord/模组名; 不再重复请求)");
		}
		if (runtimeSeen.get() > 0) {
			out.add("运行中碰到的待译键: 已补译 " + runtimeDone.get() + "/" + runtimeSeen.get()
					+ " 条(检测到就立刻打包发出去, 不计入上面的进度)");
		}
		if (skippedKeys() > 0) out.add("已跳过 " + skippedKeys() + " 条(重试 " + MAX_RETRY_ROUNDS + " 轮仍失败, 点「开始扫描」可重试)");
		out.add("译文缓存: " + cache.size() + " 条, 本次启动累计翻译 " + translatedTotal.get() + " 条");
		if (apiKeyProblem()) out.add("§c已暂停: API Key/地址有问题, 改好后自动继续");
		if (scanKnown()) {
			out.add("最近扫描: 命名空间 " + scanNamespaces.get() + " 个, 英文键 " + scanEnKeys()
					+ " 个, 已有中文 " + scanZhCoveredKeys() + " 个, 缺中文 " + missingKeys()
					+ " 个" + (invalidKeys() > 0 ? "(另有 " + invalidKeys() + " 个规则上不可翻译)" : "")
					+ ", 本次入队 " + scanQueuedKeys() + " 个");
			out.add("扫描对象/时间: " + (lastScanScope == null ? "-" : lastScanScope) + " · "
					+ new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(lastScanAt)));
		} else {
			out.add("尚未扫描: 打开游戏的开始菜单/进入世界会自动扫一次, 或点上面「开始扫描」");
		}
		out.add("资源包: " + (packEnabled() ? "已启用(随游戏正常加载，不额外重载)"
				: packFilesReady() ? "已生成，下次启动生效" : "未生成")
				+ " · " + LangPackWriter.lastKeyCount() + " 键 / " + LangPackWriter.lastNamespaceCount() + " 命名空间");
		out.add("实时译文注入点: " + (HookGuard.isBroken()
				? "§c本次会话已熔断(注入点出过错, 已自动停用; 游戏不受影响, 已译内容仍由资源包生效)"
				: "§a正常"));
		out.add("模组清单: 已记录 " + inventory.knownCount() + " 个, 本次新增或更新 "
				+ inventory.newOrUpdated().size() + " 个"
				+ (HanhuaConfig.INSTANCE.translateOnlyNewMods ? " (只翻新增模组 + 上次没译完的)" : " (每次全量)"));
		java.util.List<String> noLang = untranslatableMods;
		if (!noLang.isEmpty()) {
			int show = Math.min(10, noLang.size());
			out.add("无法汉化 " + noLang.size() + " 个模组(完全没有语言文件, 界面文字写在代码里, 资源包改不到): "
					+ String.join("、", noLang.subList(0, show))
					+ (noLang.size() > show ? " 等" : ""));
			out.add("注: 也有个别模组自带语言文件却把配置界面文字写死在代码里(例如 Reactive Music 的配置页), "
					+ "这类键根本不存在, 任何资源包都改不到, 只能等模组作者支持");
		}
		String err = lastError;
		out.add("最近错误: " + (err == null ? "无" : err));
		return out;
	}

	/** 明细行缓存(设置界面每帧都要取一行, 缓存 0.8 秒足够, 免得每帧拼字符串) */
	private volatile java.util.List<String> detailCache = java.util.List.of();
	private volatile long detailCacheAt = 0;

	/**
	 * 设置界面「汉化进度」分类的第 index 行(实时, 最多 0.8 秒延迟)。
	 * 越界返回空串 —— Cloth 的文本行在空串时高度为 0, 所以行数变化时界面自动伸缩。
	 */
	public String detailLine(int index) {
		long now = System.currentTimeMillis();
		java.util.List<String> lines = detailCache;
		if (now - detailCacheAt > 800L || lines.isEmpty()) {
			lines = progressDetails();
			detailCache = lines;
			detailCacheAt = now;
		}
		return index >= 0 && index < lines.size() ? lines.get(index) : "";
	}

	// ---- 扫描 ----
	// 只扫两次(用户要求): 打开游戏的开始菜单一次 + 进入世界一次; 没填 Key 或关掉总开关就不扫。
	// 手动按钮/命令随时可用, 手动 = 所有没中文的键都翻(不管"只翻新增模组"开关)。

	/**
	 * 所有命名空间里"player 现在已经能看到中文"的键(所有 zh_cn 并集; 含我们自己的资源包)。
	 *
	 * 注意: 这里必须用 listResourceStacks(把每个包的资源都列出来), 不能用 listResources ——
	 * 实测 listResources 在某些状态下读不到原版自带的 minecraft:lang/zh_cn.json(只读到 1771 个键),
	 * 会导致"原版 8000 多条被当成缺中文"从而误入队、误花 API 钱(安全阀就是为此兜底)。
	 */
	private Set<String> loadAllZhKeys(Minecraft mc) {
		Set<String> zh = new java.util.HashSet<>();
		int files = 0;
		try {
			var stacks = mc.getResourceManager().listResourceStacks("lang",
					id -> id.getPath().endsWith("zh_cn.json"));
			for (var entry : stacks.entrySet()) {
				for (net.minecraft.server.packs.resources.Resource res : entry.getValue()) {
					files++;
					try (java.io.InputStream in = res.open()) {
						net.minecraft.locale.Language.loadFromJson(in, (k, v) -> zh.add(k));
					} catch (Exception ignored) { }
				}
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] 读取 zh_cn 集合失败: {}", t.toString());
		}
		HanhuaClient.LOGGER.info("[Hanhua] 读取中文资源: {} 份 zh_cn, 共 {} 个键", files, zh.size());
		return zh;
	}

	/**
	 * 客户端当前语言表(玩家实际看到的那张表): 原版 + 所有模组 + 所有资源包 + 我们的包, 已合并成"键 → 生效值"。
	 * 拿不到时返回 null(资源还没加载好 / 当前不是 ClientLanguage), 调用方退回旧口径。
	 */
	private static Map<String, String> effectiveLanguageTable() {
		try {
			net.minecraft.locale.Language lang = net.minecraft.locale.Language.getInstance();
			if (lang instanceof HanhuaLanguageAccess access) {
				Map<String, String> m = access.hanhua$storage();
				if (m != null && !m.isEmpty()) return m;
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] 读取客户端语言表失败: {}", t.toString());
		}
		return null;
	}

	/**
	 * "这个键现在真的有中文吗" —— 0.4.7 的新口径: 看**语言表里的实际生效值**(= 玩家看到的文字)。
	 *
	 * <p>判定顺序:
	 * <ol>
	 *   <li>本地缓存/保持原文里有这个键 → 有(已经译好, 下一次写包/重载就生效);</li>
	 *   <li>生效值含中文 → 有;</li>
	 *   <li>生效值与英文原文不同(作者有意保留的 OK / PvP / Ctrl 之类) → 有;</li>
	 *   <li>生效值就是英文原文, 但那段文字根本不该翻译(TNT / ID / %s (%s) 这类记号 —— 原版 zh_cn 里就有 385 条)
	 *       → 有(不算缺, 免得白花钱还把 "TNT" 翻成奇怪的东西);</li>
	 *   <li>生效值就是英文原文, 而且是正常英文词句(键存在但值还是英文 → 界面显示英文) → **没有, 要翻**。</li>
	 * </ol>
	 * 旧口径只看"键在不在某个 zh_cn.json 里", 所以这些"值还是英文"的键被算成已有中文, 进度 100% 界面英文。
	 */
	private boolean hasChineseNow(String key, String enValue, Set<String> zhAll, Map<String, String> effective) {
		if (cache.get(key) != null || keepOriginal.contains(key)) return true;
		String eff = effective == null ? null : effective.get(key);
		if (eff != null) {
			if (KeyRules.containsCjk(eff)) return true;
			if (enValue != null && !eff.trim().equalsIgnoreCase(enValue.trim())) return true;
			return !KeyRules.looksLikeTranslatableProse(enValue);
		}
		return zhAll.contains(key);      // 语言表读不到 -> 退回旧口径(尽力而为, 由安全阀兜底)
	}

	/** 所有带 en_us.json 的命名空间 */
	private static java.util.List<String> allEnNamespaces(Minecraft mc) {		java.util.TreeSet<String> ns = new java.util.TreeSet<>();
		mc.getResourceManager().listResources("lang", id -> id.getPath().endsWith("en_us.json")).keySet()
				.forEach(id -> ns.add(id.getNamespace()));
		return new java.util.ArrayList<>(ns);
	}

	/** modid → 它 jar 里的 assets 命名空间 */
	private static Set<String> namespacesOf(java.util.Collection<String> modIds) {
		Set<String> ns = new java.util.LinkedHashSet<>();
		if (modIds == null) return ns;
		for (String id : modIds) {
			if (id == null || id.isBlank()) continue;
			try {
				ns.addAll(ModInventory.assetNamespacesOf(id));
			} catch (Throwable ignored) { }
		}
		return ns;
	}

	/**
	 * 一次完整扫描(本地读取, 不发请求):
	 * - 统计覆盖率: en 键 / 已有中文 / 缺中文 / 规则上不可翻译;
	 * - 算"本次要翻的活": planAll ? 所有缺中文的键 : (新增/更新模组 ∪ 上次没译完的命名空间) 里的缺键;
	 * - 把这些键入队(带英文原文), 之后由后台一直重试到全部有结果。
	 *
	 * @return 入队键数; -1 = 条件不满足(未启用/没 Key/资源未就绪)
	 */
	private int scanInternal(boolean planAll, String trigger) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getResourceManager() == null) return -1;
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!started.get() || !c.enabled) {
			HanhuaClient.LOGGER.info("[Hanhua] {} 跳过: enabled={}", trigger, c.enabled);
			return -1;
		}
		if (!c.hasApiKey()) {
			// 无 Key 也照常扫描: 统计缺键 + 吸收社区包现成译文, 只是不登记不入队(不花 API 钱);
			// 填 Key 后 onApiKeySet 会再扫一次把剩下的键登记进队列
			HanhuaClient.LOGGER.info("[Hanhua] {}: 无 Key, 仅扫描统计 + 社区包吸收(不请求 API)", trigger);
		}
		if (!scanRunning.compareAndSet(false, true)) {
			HanhuaClient.LOGGER.info("[Hanhua] {} 跳过: 已有扫描在进行", trigger);
			return 0;
		}
		try {
			Set<String> zhAll = loadAllZhKeys(mc);
			Map<String, String> effective = effectiveLanguageTable();   // 玩家实际看到的值(0.4.7 口径)
			java.util.List<String> namespaces = allEnNamespaces(mc);
			// 安全阀: 如果 zh_cn 只读到很少的键(资源还没加载好 / 读取出了问题), 绝不据此大批量入队 ——
			// 那会给用户造成一大笔 API 费用。等几秒重试, 最多 SMALL_ZH_RETRIES 次。
			boolean zhSuspect = zhAll.size() < MIN_SANE_ZH_KEYS && namespaces.size() > 3;
			if (zhSuspect) {
				if (zhRetryCount < SMALL_ZH_RETRIES) {
					zhRetryCount++;
					HanhuaClient.LOGGER.warn("[Hanhua] {} 放弃本次扫描: 只读到 {} 个中文键(资源可能还没加载完), {} 秒后重试 ({}/{})",
							trigger, zhAll.size(), 3 * zhRetryCount, zhRetryCount, SMALL_ZH_RETRIES);
					scheduler.schedule(() -> scanInternal(planAll, trigger), 3L * zhRetryCount, TimeUnit.SECONDS);
					return -1;
				}
				HanhuaClient.LOGGER.warn("[Hanhua] {} 仍然只读到 {} 个中文键 -> 本次只统计、不入队(避免误花 API 钱)",
						trigger, zhAll.size());
			} else {
				zhRetryCount = 0;
			}
			// 计划(承诺翻译)哪些命名空间
			Set<String> plan = new java.util.LinkedHashSet<>();
			String planDesc;
			if (planAll || !c.translateOnlyNewMods) {
				plan.addAll(namespaces);
				planDesc = planAll ? "手动全量" : "全量(剩下的键也一并译完)";
			} else {
				Set<String> changed = namespacesOf(inventory.newOrUpdated());
				plan.addAll(changed);
				int changedCount = plan.size();
				plan.addAll(pendingScopeNamespaces);
				planDesc = "新增模组 " + changedCount + " 个命名空间 + 续译 " + pendingScopeNamespaces.size() + " 个";
			}
			if (zhSuspect) {
				plan.clear();                        // 资源异常: 只统计覆盖率, 一个键都不入队
				planDesc = "资源读取异常, 只统计不翻译";
			} else if (planAll) {
				// 手动扫描: 确定要真正入队了才清放弃名单(放在 zhSuspect 判定之后, 防"清了名单却没重试")
				skippedKeys.clear();                 // 给之前放弃过的键一次新机会
				// 注意: "保持原文"名单**不清** —— 那是模型已经判定"原文本来就该是英文"的键(Shift/Discord/模组名),
				// 再问一遍结论也不会变, 却会让它们从"已覆盖"掉回"未译": 无 Key 时根本无法重判(永远挂在未译),
				// 有 Key 时也是白花一次请求。0.5.5 实测: 手动扫描把覆盖率从 100% 打到 98%, 凭空多出 163 条"未译"。
			}
			// 重置统计
			scanNamespaces.set(0);
			scanEnTotal.set(0);
			scanZhTotal.set(0);
			scanMissingTotal.set(0);
			scanQueuedTotal.set(0);
			scanInvalidTotal.set(0);
			scanModEnTotal.set(0);
			scanModZhTotal.set(0);
			scanCommunityAbsorbed.set(0);
			// 进度计数**不清零**(用户明确要求"显示进度和实际进度一致", 重扫打回 0% 不合理):
			// 只是把上一次会话留下的、这次扫描会重新登记的部分折算掉 —— 但**在途/排队中的键必须保留**,
			// 否则会出现"还有 50 条在跑却显示 100%"(用户实测)。
			if (!workCountersAligned) {
				workCountersAligned = true;
				workTotalCount.set(workDoneCount.get() + workKeys.size());
				HanhuaClient.LOGGER.info("[Hanhua] 进度计数对齐: 已译 {} + 在册 {} = 总数 {}",
						workDoneCount.get(), workKeys.size(), workTotalCount.get());
			}
			scanUnitsTotal.set(namespaces.size());
			scanUnitsDone.set(0);
			// 注意: committedNamespaces 不能在这里 clear —— 扫描循环逐个命名空间往里 add,
			// 若中途崩溃/被强杀, 提前清空会让快照只留下已扫完的几个命名空间, 其余续译承诺静默丢失。
			// clear 挪到扫描循环完成之后(见下方 lastScanAt 附近)。
			scanUnitsRunning = true;
			int queued = 0;
			try {
				for (String ns : namespaces) {
					queued += scanOneNamespace(mc, ns, zhAll, effective, plan.contains(ns), false);
					scanUnitsDone.incrementAndGet();
				}
			} finally {
				scanUnitsRunning = false;
			}
			// 模组名/简介(fabric.mod.json 里的英文, ModMenu 列表直接显示) —— 这些键不在任何 en_us.json 里,
			// 扫不到, 所以主动补上; 数量很少(每个模组 2 条), 一次请求就够, 于是不受"只翻译新增模组"限制。
			int modInfoQueued = 0;
			if (!zhSuspect && c.hasApiKey()) {   // 无 Key: 模组名/简介也不登记(登记了也发不出去)
				modInfoQueued = planModInfoTranslations();
				if (modInfoQueued > 0) {
					HanhuaClient.LOGGER.info("[Hanhua] 模组名/简介: 登记 {} 条(modmenu.nameTranslation/descriptionTranslation)",
							modInfoQueued);
				}
				queued += modInfoQueued;
			}
			try {
				untranslatableMods = modsWithoutLang(mc);
			} catch (Throwable t) {
				HanhuaClient.LOGGER.debug("[Hanhua] 统计无语言文件模组失败: {}", t.toString());
			}
			lastScanAt = System.currentTimeMillis();
			scannedThisSession = true;
			lastScanScope = trigger + " · " + planDesc;
			// 先把完整的承诺名单拷给续译范围; persistProgress 用 committedNamespaces 写快照 scope;
			// 最后才清空(下次扫描逐命名空间重建) —— 顺序不能反, 中断路径永远保留旧名单
			pendingScopeNamespaces.clear();
			pendingScopeNamespaces.addAll(committedNamespaces);
			persistProgress();
			committedNamespaces.clear();
			HanhuaClient.LOGGER.info("[Hanhua] {}: {} 个命名空间, 已有中文 {}/{}, 待译 {}, 入队 {}, 不可译 {} ({})",
					trigger, namespaces.size(), scanZhTotal.get(), scanZhTotal.get() + scanMissingTotal.get(),
					scanMissingTotal.get(), queued, scanInvalidTotal.get(), planDesc);
			return queued;
		} catch (Throwable t) {
			HanhuaClient.LOGGER.warn("[Hanhua] {} 失败", trigger, t);
			return -1;
		} finally {
			scanRunning.set(false);
		}
	}

	/** 扫一个命名空间: 统计 + (inPlan 时)把缺中文的键登记进"本次要译"并排队 */
	private int scanOneNamespace(Minecraft mc, String namespace, Set<String> zhAll,
			Map<String, String> effective, boolean inPlan, boolean forceRetrySkipped) {
		Map<String, String> en = new HashMap<>();
		try {
			var stacks = mc.getResourceManager().listResourceStacks("lang",
					id -> id.getNamespace().equals(namespace) && id.getPath().endsWith("en_us.json"));
			for (var entry : stacks.entrySet()) {
				for (net.minecraft.server.packs.resources.Resource res : entry.getValue()) {
					try (java.io.InputStream in = res.open()) {
						net.minecraft.locale.Language.loadFromJson(in, en::putIfAbsent);
					} catch (Exception ex) {
						HanhuaClient.LOGGER.debug("[Hanhua] scan failed reading {}: {}", entry.getKey(), ex.toString());
					}
				}
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] scan '{}' 读取失败: {}", namespace, t.toString());
		}
		int queued = 0, covered = 0, missing = 0, invalid = 0, commHit = 0;
		for (Map.Entry<String, String> e : en.entrySet()) {
			String key = e.getKey();
			if (!KeyRules.looksTranslatable(key, e.getValue())) { invalid++; continue; }
			if (hasChineseNow(key, e.getValue(), zhAll, effective)) {
				covered++;
				// 已经（被别处的资源补上/刚译好）有中文了 -> 从"待译"里摘掉, 免得承诺永远清不掉
				if (committedKeys.remove(key)) {
					committedText.remove(key);
					retryAt.remove(key);
					retryRounds.remove(key);
					pending.remove(key);
					if (workKeys.remove(key)) workDoneCount.incrementAndGet();
				}
				continue;
			}
			missing++;
			scanMissingTotal.incrementAndGet();      // 缺中文的键数(含老模组里没在计划内的, 明细里如实显示)
			if (inPlan && (forceRetrySkipped || !skippedKeys.contains(key))) {
				// 手动重扫: 只对"本次计划内"的键取消跳过名单(不做全局 clear, 免得波及无关模块)
				if (forceRetrySkipped) skippedKeys.remove(key);   // 必须在 enqueueIfRoom 之前(它内部也查 skippedKeys)
				// 社区包优先(P3): 有现成译文直接吸收, 不入队不调 API
				String community = CommunityPackManager.INSTANCE.lookup(key);
				if (community != null) {
					adoptCommunity(key, e.getValue(), community);
					communityHits.incrementAndGet();
					commHit++;
					continue;
				}
				// 无 Key: 只统计缺键数, 不登记不入队(等社区包就绪回调补扫 / 用户填 Key 后再扫)
				if (!HanhuaConfig.INSTANCE.hasApiKey()) continue;
				commitKey(key, e.getValue());
				committedNamespaces.add(namespace);
				if (enqueueIfRoom(key, e.getValue())) queued++;
			}
		}
		scanNamespaces.incrementAndGet();
		if (commHit > 0) scanCommunityAbsorbed.addAndGet(commHit);
		scanEnTotal.addAndGet(en.size());
		scanZhTotal.addAndGet(covered);
		scanInvalidTotal.addAndGet(invalid);
		scanQueuedTotal.addAndGet(queued);
		if (isModNamespace(namespace)) {
			scanModEnTotal.addAndGet(en.size());
			scanModZhTotal.addAndGet(covered);
		}
		if (missing > 0) {
			HanhuaClient.LOGGER.info("[Hanhua] scan '{}': en={} 已有中文={} 待译={} -> 社区包吸收 {}{}",
					namespace, en.size(), covered, missing, commHit,
					queued > 0 ? ", 入队 " + queued : "(零 API 请求)");
		}
		return queued;
	}

	/** 手动全量扫描(设置页按钮 / `/hanhua scan all`): 所有缺中文的键都翻 */
	public int scanAll() {
		return scanInternal(true, "手动全量扫描");
	}

	/** 自动扫描(开始菜单 / 进世界): 新增模组 ∪ 上次没译完的 */
	public int scanAutomatic(String trigger) {
		return scanInternal(false, trigger);
	}

	/** 手动扫单个命名空间(`/hanhua scan <modid>`) */
	public int scanNamespace(String namespace) {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!started.get() || !c.enabled) return -1;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.getResourceManager() == null) return -1;
		if (!scanRunning.compareAndSet(false, true)) {
			HanhuaClient.LOGGER.info("[Hanhua] 单命名空间扫描跳过: 已有扫描在进行");
			return 0;
		}
		try {
			Set<String> zh = loadAllZhKeys(mc);
			// 只对本命名空间取消"跳过"名单(原来是全局 skippedKeys.clear(), 会波及无关模块)
			return scanOneNamespace(mc, namespace, zh, effectiveLanguageTable(), true, true);
		} finally {
			scanRunning.set(false);
		}
	}

	/**
	 * 模组名/简介: ModMenu 列表里显示的是 fabric.mod.json 里的英文, 但只要有
	 * {@code modmenu.nameTranslation.<modid>} / {@code modmenu.descriptionTranslation.<modid>} 这两个键,
	 * ModMenu 就会用我们的译文。这两个键不在任何 en_us.json 里(只在翻译里存在), 扫描扫不到 → 这里主动登记。
	 */
	private int planModInfoTranslations() {
		int queued = 0;
		try {
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				if ("minecraft".equals(id) || "java".equals(id) || "fabricloader".equals(id) || "hanhua".equals(id)) continue;
				queued += planOneText("modmenu.nameTranslation." + id, mod.getMetadata().getName());
				String desc = mod.getMetadata().getDescription();
				if (desc != null) {
					String flat = desc.replace("\r", "").replace("\n", " ").trim();
					queued += planOneText("modmenu.descriptionTranslation." + id, flat);
				}
			}
		} catch (Throwable t) {
			HanhuaClient.LOGGER.debug("[Hanhua] 模组名/简介登记失败: {}", t.toString());
		}
		return queued;
	}

	private int planOneText(String key, String english) {
		if (english == null || english.isBlank()) return 0;
		if (!KeyRules.looksTranslatable(key, english)) return 0;
		if (cache.get(key) != null || keepOriginal.contains(key) || skippedKeys.contains(key)) return 0;
		commitKey(key, english);
		committedNamespaces.add("modmenu");
		return enqueueIfRoom(key, english) ? 1 : 0;
	}

	/**
	 * 完全没有语言文件的模组(界面文字写死在代码里) —— 这类资源包改不到, 明细里如实列出来,
	 * 免得用户以为是漏翻了(用户要求)。
	 */
	private static java.util.List<String> modsWithoutLang(Minecraft mc) {
		java.util.List<String> out = new java.util.ArrayList<>();
		try {
			Set<String> enNs = new java.util.HashSet<>(allEnNamespaces(mc));
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				String id = mod.getMetadata().getId();
				if ("minecraft".equals(id) || "java".equals(id) || "fabricloader".equals(id) || "hanhua".equals(id)) continue;
				boolean has = false;
				for (String ns : namespacesOf(java.util.List.of(id))) {
					if (enNs.contains(ns)) { has = true; break; }
				}
				if (!has) out.add(mod.getMetadata().getName());
			}
			java.util.Collections.sort(out);
		} catch (Throwable ignored) { }
		return out;
	}

	private boolean enqueueIfRoom(String key, String english) {
		if (!HanhuaConfig.INSTANCE.hasApiKey()) return false;
		if (skippedKeys.contains(key)) return false;
		if (inflight.contains(key) || pending.containsKey(key)) return false;
		if (pending.size() >= MAX_PENDING) return false;
		pending.put(key, english);
		return true;
	}

	/** 开始菜单扫描(每次启动一次; 只读本地资源, 不花钱) */
	public void scanOnTitleScreenAsync() {
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.enabled) {
			HanhuaClient.LOGGER.info("[Hanhua] 开始菜单不扫描: enabled={}", c.enabled);
			return;
		}
		if (!c.worldLoadScan) {
			HanhuaClient.LOGGER.info("[Hanhua] 开始菜单不扫描: 已关闭「进入世界检查新增模组」");
			return;
		}
		scheduler.schedule(() -> scanAutomatic("开始菜单扫描"), 3, TimeUnit.SECONDS);
	}

	/**
	 * 进入世界时: 发送欢迎语 + 扫一次(和开始菜单那次一样: 新增模组 ∪ 上次没译完的)。
	 * 只扫这一次, 之后由后台重试机制一直译到全部有结果。
	 */
	public void scanOnWorldJoinAsync() {
		sendWelcomeAsync();
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.worldLoadScan || !c.enabled) {
			worldScanDone.set(true);  // 不满足条件也视为"已处理",避免反复尝试
			HanhuaClient.LOGGER.info("[Hanhua] 进入世界不扫描: enabled={}, worldLoadScan={}",
					c.enabled, c.worldLoadScan);
			return;
		}
		if (!worldScanDone.compareAndSet(false, true)) return;
		scheduler.schedule(() -> {
			int q = scanAutomatic("进入世界扫描");
			inventory.update();   // 记录本次已知集合, 下次不再重复请求
			Minecraft mc = Minecraft.getInstance();
			if (q > 0 && mc != null) {
				final int fq = q;
				mc.execute(() -> {
					if (mc.player != null) {
						mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
								"§a【COC的DS汉化】已入队 " + fq + " 个待翻译键, 后台自动翻译到全部完成(结果会存成资源包)"));
					}
				});
			}
		}, 2, TimeUnit.SECONDS);
	}

	private void sendWelcomeAsync() {
		if (!HanhuaConfig.INSTANCE.enabled) return;
		scheduler.schedule(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null) return;
			mc.execute(() -> {
				if (mc.player != null) {
					mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("【欢迎使用COC的DS汉化mod】"));
				}
			});
		}, 1, TimeUnit.SECONDS);
	}

	/** 离开世界/连接断开时重置会话标记, 以便下次进图再次预扫 */
	public void resetWorldScanFlag() {
		worldScanDone.set(false);
	}

	// ---- 状态/管理(供命令使用) ----
	public int cacheSize() { return cache.size(); }
	public int pendingSize() { return pending.size(); }
	public int inflightSize() { return inflight.size(); }
	public long translatedTotal() { return translatedTotal.get(); }
	public String lastError() { return lastError; }
	public long lastErrorAt() { return lastErrorAt; }

	/** API Key 是否被服务端判过无效/欠费(设置界面用来决定要不要显示提示行) */
	public boolean apiKeyProblem() { return apiKeyProblem != null; }

	/** 提示行文案: 空串 = 不显示(此时 Cloth 里这一行高度是 0, 不留空白) */
	public String apiKeyHintLine() { return apiKeyProblem == null ? "" : apiKeyProblem; }

	/** 用户重新填写/清空 Key 之后调用: 清掉旧提示并**自动继续**之前暂停的翻译 */
	public void clearApiKeyProblem() {
		boolean wasPaused = apiKeyProblem != null;
		apiKeyProblem = null;
		if (wasPaused) {
			retryAt.keySet().removeAll(committedKeys);   // 清掉暂停期间残留的退避, 立刻继续
			HanhuaClient.LOGGER.info("[Hanhua] API Key 已更新 -> 自动继续翻译 (在册剩 {} 条, 队列 {} 条)",
					committedKeys.size(), pending.size());
		}
	}

	/**
	 * 用户在设置页/弹窗/命令里**填好 Key**之后调用(用户要求"填入 api key 后就开始工作"):
	 * 1) 解除"Key 有问题"造成的暂停, 清掉退避让队列立刻继续;
	 * 2) 本会话还没扫过(比如启动时没填 Key 被跳过了) -> 马上补一次扫描并开始翻译。
	 */
	public void onApiKeySet() {
		clearApiKeyProblem();
		HanhuaConfig c = HanhuaConfig.INSTANCE;
		if (!c.enabled || !c.worldLoadScan) return;
		if (!scannedThisSession || !workKeys.isEmpty() || scanMissingTotal.get() > scanCommunityAbsorbed.get()) {
			// 没扫过 / 还有未译键 / 无 Key 阶段扫出了"社区包也没有"的缺键(当时只统计没登记) -> 重扫一次登记原文,
			// 保证"填 Key 后继续汉化剩余部分"(0.5.3 实测: 无 Key 扫描后填 Key 不补扫, 剩余键只能手动点扫描才会登记)
			HanhuaClient.LOGGER.info("[Hanhua] 填好 Key -> 扫描并开始翻译 (在册未译 {} 条, 缺键 {} 条)", workKeys.size(), scanMissingTotal.get());
			scheduler.schedule(() -> scanAutomatic("填入Key后扫描"), 1, TimeUnit.SECONDS);
		} else if (!pending.isEmpty() || !committedKeys.isEmpty()) {
			HanhuaClient.LOGGER.info("[Hanhua] 填好 Key -> 继续队列 (待发 {}, 在册剩 {})",
					pending.size(), committedKeys.size());
		}
	}

	public void clearCache() {
		cache.clear();
		pending.clear();
		retryAt.clear();
		retryRounds.clear();
		inflight.clear();
		committedKeys.clear();
		committedText.clear();
		skippedKeys.clear();
		committedNamespaces.clear();
		workKeys.clear();
		workDoneCount.set(0);
		workTotalCount.set(0);
		workCountersAligned = false;
		// keepOriginal **不清**: 它记录的是"模型判定原文本来就该是英文"的键(Shift/Discord/模组名),
		// 属于判定结果而不是译文缓存; 清掉它们只会让这些键在下次扫描里凭空变成"未译"(0.5.5 实测坑)。
		translatedTotal.set(0);
		lastError = null;
		apiKeyProblem = null;
		lastActivityAt = System.currentTimeMillis();
		cache.save();
		LangPackWriter.deletePack();
		HanhuaClient.LOGGER.info("[Hanhua] translation cache cleared (保留 {} 条'保持原文'判定)", keepOriginal.size());
	}

	// ---- 端到端自检(仅 HANHUA_SELFTEST=1 时调用, 绕过语言闸门直连真实 API) ----
	public void selftest() {
		if (!HanhuaConfig.INSTANCE.hasApiKey()) {
			HanhuaClient.LOGGER.warn("[Hanhua][selftest] skipped: no api key in config");
			return;
		}
		Map<String, String> samples = new java.util.LinkedHashMap<>();
		samples.put("item.hanhua_test.rubber_duck", "Rubber Duck");
		samples.put("item.hanhua_test.ender_rose", "Ender Rose");
		samples.put("item.hanhua_test.soul_gear", "Soul Gear of the Nether");
		samples.put("menu.hanhua_test.placeholders", "Welcome to %1$s, adventurer %2$s!");
		samples.put("menu.hanhua_test.colored", "§aGreen Apple");
		for (Map.Entry<String, String> e : samples.entrySet()) {
			pending.put(e.getKey(), e.getValue());
		}
		HanhuaClient.LOGGER.info("[Hanhua][selftest] enqueued {} samples for real API translation", samples.size());
		scheduler.schedule(() -> {
			StringBuilder sb = new StringBuilder("[Hanhua][selftest] results:");
			for (String k : samples.keySet()) {
				String v = cache.get(k);
				sb.append("\n  ").append(k).append(" = ").append(v == null ? "<MISSING>" : "\"" + v + "\"");
			}
			HanhuaClient.LOGGER.info("{}", sb);
			HanhuaClient.LOGGER.info("[Hanhua][selftest] lastError={}, translatedTotal={}", lastError, translatedTotal.get());
			cache.save();
		}, 25, TimeUnit.SECONDS);
	}
}
