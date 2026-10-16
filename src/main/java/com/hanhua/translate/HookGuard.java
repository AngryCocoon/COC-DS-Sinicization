package com.hanhua.translate;

import com.hanhua.HanhuaClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注入点保险丝(0.4.8 新增)。
 *
 * <p>背景(用户实测): {@code ClientLanguage#getOrDefault} 在**渲染热路径**上, 每一帧都要被调用几千次
 * (物品名/tooltip/按钮/聊天/HUD...)。这个入口上只要抛出一个异常, 就会顺着原版代码冒到渲染线程,
 * 变成"汉化模组把游戏搞崩了"的崩溃报告 —— 崩溃栈里全是 hanhua, 用户看到的也是这个。
 *
 * <p>所以这里做两件事:
 * <ol>
 *   <li>{@link #reportFailure} 第一次出错就**永久停用**该注入点(本次会话), 并且只报一次日志;
 *       之后所有语言查询都走原版逻辑 —— 游戏照常运行, 只是少了"实时译文"这一路;</li>
 *   <li>{@link #checkFaultInjection} 提供自检用的故障注入({@code HANHUA_SELFTEST_HOOK_THROW=1}),
 *       用来在真机验收里证明"注入点抛异常时游戏不崩"。</li>
 * </ol>
 */
public final class HookGuard {
	/** 语言查询注入点 */
	public static final String HOOK_LANGUAGE_LOOKUP = "语言查询注入点(ClientLanguage#getOrDefault)";

	/** 注入点是否已熔断(熔断后所有调用直接放行, 不再执行我们的逻辑) */
	private static final AtomicBoolean BROKEN = new AtomicBoolean(false);

	/** 自检: 是否注入一次故障 */
	private static final boolean FAULT_INJECT = "1".equals(System.getenv("HANHUA_SELFTEST_HOOK_THROW"));
	private static final AtomicBoolean FAULT_DONE = new AtomicBoolean(false);
	/** 自检结果: 熔断是否真的被触发(供 /hanhua status 与验收日志确认) */
	private static final AtomicBoolean FAULT_TRIGGERED = new AtomicBoolean(false);

	private HookGuard() { }

	public static boolean isBroken() {
		return BROKEN.get();
	}

	public static boolean faultInjectionTriggered() {
		return FAULT_TRIGGERED.get();
	}

	/** 自检: 第一次调用时故意抛异常(仅 HANHUA_SELFTEST_HOOK_THROW=1 时生效) */
	public static void checkFaultInjection() {
		if (FAULT_INJECT && FAULT_DONE.compareAndSet(false, true)) {
			throw new IllegalStateException("injected language-hook failure (HANHUA_SELFTEST_HOOK_THROW=1)");
		}
	}

	/** 注入点出错: 熔断并只报一次(把异常吞掉, 绝不交给游戏) */
	public static void reportFailure(String hook, Throwable t) {
		if (BROKEN.compareAndSet(false, true)) {
			FAULT_TRIGGERED.set(true);
			HanhuaClient.LOGGER.error("[Hanhua] {} 出错 -> 本次会话永久停用该注入点"
					+ "(游戏不受影响, 只是少了实时译文; 已译内容仍由资源包生效)", hook, t);
		}
	}
}
