package com.hanhua.translate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 翻译候选判定与占位符保护规则 */
public final class KeyRules {
	/**
	 * 真·格式占位符 + 颜色代码。
	 *
	 * <p>注意(0.4.7 修的坑): 结尾的 {@code (?![A-Za-z])} 很关键 —— 文本里的**裸百分号**(
	 * 例如 JEI 的 "only when % is typed first, like %building.")以前会被当成占位符 "%b"/"% i",
	 * 于是模型给的中文译文"占位符不符"、重试 3 轮后被放弃, 用户看到的就是"卡在 99%"。
	 * 现在只有"后面不再跟字母"的 % 才算占位符; 同时 {@link #tokensPreserved} 加了 % 个数校验兜底。
	 */
	private static final Pattern FORMAT_TOKEN = Pattern.compile(
			"%(?:\\d+\\$)?[-#+0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z%](?![A-Za-z])|§[0-9a-fk-orxA-FK-ORX]");
	private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
	/** 英文单词(3 个以上字母): 扫描/二次确认会调用几千次, 预编译一次(0.4.8 顺手修的) */
	private static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z]{3,}");

	private KeyRules() { }

	public static boolean containsCjk(String s) {
		if (s == null) return false;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			if (isCjk(cp)) return true;
			i += Character.charCount(cp);
		}
		return false;
	}

	private static boolean isCjk(int cp) {
		return (cp >= 0x3400 && cp <= 0x4DBF)      // 扩展A
				|| (cp >= 0x4E00 && cp <= 0x9FFF)  // 基本区
				|| (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容区
				|| (cp >= 0x20000 && cp <= 0x2FA1F); // 扩展B-G
	}

	/** 是否值得翻译: 非空、长度适中、不含中文(即 zh_cn 缺失)、有实际文字内容 */
	public static boolean looksTranslatable(String key, String english) {
		if (key == null || english == null) return false;
		String v = english.trim();
		if (v.isEmpty()) return false;
		if (containsCjk(v)) return false;
		if (v.length() < 2 || v.length() > 2000) return false;  // 放过超长 tooltip(曾用300误伤)
		return LETTER_OR_DIGIT.matcher(v).find();
	}

	/**
	 * 译文必须保留原文所有格式占位符(%s、%1$s、§x),否则破坏物品栏数字/颜色。
	 * 0.4.7 起再加一条"百分号个数不能变"(裸 % 不属于占位符, 但也不能被模型吃掉/多加)。
	 */
	public static boolean tokensPreserved(String src, String dst) {
		if (src == null || dst == null) return false;
		Matcher m = FORMAT_TOKEN.matcher(src);
		while (m.find()) {
			String token = m.group();
			if (!dst.contains(token)) return false;
		}
		if (countPercent(src) != countPercent(dst)) return false;
		return true;
	}

	/** 文本里的 '％'/'%' 个数(全角也算), 用来兜住"裸百分号被吃掉"的情况 */
	public static int countPercent(String s) {
		if (s == null) return 0;
		int n = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '%' || c == '\uff05') n++;
		}
		return n;
	}

	/**
	 * 这段文字看起来是"该翻译的英文散文"吗?
	 *
	 * <p>用于两处(0.4.7):
	 * <ol>
	 *   <li>进度口径: zh_cn 里值就是英文原文的键里, {@code TNT} / {@code OK} / {@code ID} / {@code F1} /
	 *       {@code %s (%s)} 这类本来就不该翻译的记号不算"缺中文"(原版 zh_cn 里这样的键有 385 条);</li>
	 *   <li>"保持原文"二次确认: 只有这类文本被模型原样退回时才值得换严格提示再问一次。</li>
	 * </ol>
	 * 判定: 去掉 %s/§x 等记号后, 还剩**至少一个含小写字母、长度 ≥3 的英文单词**(TNT/OK/UI 这类全大写缩写不算)。
	 */
	public static boolean looksLikeTranslatableProse(String s) {
		if (s == null) return false;
		String t = FORMAT_TOKEN.matcher(s).replaceAll(" ");
		Matcher m = ENGLISH_WORD.matcher(t);
		while (m.find()) {
			String w = m.group();
			if (!w.equals(w.toUpperCase(java.util.Locale.ROOT))) return true;   // 有小写字母 -> 是真单词
		}
		return false;
	}
}
