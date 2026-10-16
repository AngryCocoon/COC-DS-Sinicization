package com.hanhua.mixin;

import com.hanhua.translate.HanhuaLanguageAccess;
import com.hanhua.translate.HookGuard;
import com.hanhua.translate.TranslationManager;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 翻译注入点(26.2 实证结构,见 NOTES.md N1):
 * ClientLanguage 用单张合并表 storage(所选语言 + en_us 兜底)。
 * 目标语言下,凡生效值仍为英文(不含 CJK)的键 = zh_cn 缺失 → 走 Hanhua 缓存/队列。
 * 命中缓存直接改写返回值;未命中先返回原文,后台翻译完成后下一帧自动生效。
 *
 * <p>0.4.8 加固(用户报"启动崩溃、崩溃日志指向汉化"):
 * 这个入口在渲染热路径上, 每帧被调用几千次 —— 我们在这里抛出的任何异常都会冒到渲染线程,
 * 变成"汉化模组崩游戏"的崩溃报告。现在整段逻辑包在 try/catch 里, 出错即熔断({@link HookGuard}),
 * 从此所有查询原样走原版逻辑, 游戏不受影响。
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin implements HanhuaLanguageAccess {
	@Shadow
	private Map<String, String> storage;

	/**
	 * 把"玩家实际看到的这张语言表"交给扫描用(0.4.7 进度口径): 键存在但值是英文原文 = 还没中文。
	 * 只读, 不修改; 语言重载会换一个新的 ClientLanguage 实例, 这张表本身不再变。
	 */
	@Override
	public Map<String, String> hanhua$storage() {
		return storage;
	}

	@Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
	private void hanhua$translate(String key, String fallback, CallbackInfoReturnable<String> cir) {
		if (HookGuard.isBroken()) return;                 // 已熔断: 直接放行, 一行代码都不跑
		try {
			HookGuard.checkFaultInjection();              // 自检用故障注入(正常游戏里不生效)
			TranslationManager manager = TranslationManager.INSTANCE;
			if (!manager.active()) return;
			Map<String, String> table = storage;
			if (table == null) return;                    // 表还没建好: 放行
			String english = table.get(key);
			if (english == null) return;                  // 完全缺失的裸键:留给原版兜底显示
			String translated = manager.resolve(key, english);
			if (translated != null) {
				cir.setReturnValue(translated);
			}
		} catch (Throwable t) {
			// 绝不把异常交给游戏: 熔断自己, 本次会话不再介入语言查询
			HookGuard.reportFailure(HookGuard.HOOK_LANGUAGE_LOOKUP, t);
		}
	}
}
