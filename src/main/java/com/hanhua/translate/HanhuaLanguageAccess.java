package com.hanhua.translate;

import java.util.Map;

/**
 * 由 {@link com.hanhua.mixin.ClientLanguageMixin} 实现: 把客户端当前语言表(合并后的 storage)暴露出来。
 *
 * <p>用途(0.4.7 起): "有没有中文"必须按**玩家实际看到的值**判定, 而不是按"某个 zh_cn.json 里有没有这个键"。
 * 旧口径把"键存在但值还是英文原文"的键算成已有中文, 于是进度显示 100% 而界面仍是英文(用户实测反馈)。
 * 这张表就是 {@code Language.getInstance()} 解析用的那张表(原版 + 所有模组 + 所有资源包 + 我们的包),
 * 读它得到的值 = 游戏里真正渲染出来的文字。
 */
public interface HanhuaLanguageAccess {
	/** 客户端语言表(键 → 当前语言下的生效值); 拿不到时返回 null */
	Map<String, String> hanhua$storage();
}
