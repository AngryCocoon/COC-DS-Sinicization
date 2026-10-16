package com.hanhua.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.hanhua.config.HanhuaConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DeepSeek OpenAI 兼容 /chat/completions 批量翻译客户端。
 * 输入 {key: 英文原文} → 输出 {key: 简体中文}。
 */
public final class DeepSeekApi {

	public static final class ApiException extends Exception {
		public final int status;

		public ApiException(int status, String msg) {
			super(msg);
			this.status = status;
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private static final String SYS_PROMPT =
			"你是精通 Minecraft 模组汉化的翻译助手。用户会给你若干条英文文本(可能带 %s、%1$s 等格式化占位符和 § 颜色代码)。"
			+ "请把它们逐条翻译成简体中文,并只输出一个 JSON 对象:键为每条输入里的 key 字段原样,值为对应译文。"
			+ "规则:1) 必须把 %s、%1$s、§x 等占位/颜色代码原样保留在译文中;"
			+ "2) 术语遵循中文 Minecraft 社区习惯(如 Diamond=钻石、Enchanting Table=附魔台);"
			+ "3) 如果某条本来就该保持英文(例如 Shift/Ctrl 这类按键名、Discord 这类专有名词、模组名), 就把原文原样放进 JSON, 不要强行音译;"
			+ "4) 只输出 JSON,不要任何解释、前后缀或代码块标记。";

	/**
	 * 严格提示词(0.4.7): 模型第一次把原文原样退回、但这条文本明明是可翻译的英文词时, 用这套再问一次。
	 * 起因: 实测 'Inventory'、'Light Updates Warning' 这类普通词被原样退回后会被记成"保持原文"永不重译
	 * (用户反馈"配置菜单没汉化, 进度却 100%")。品牌名再问一次也还是原样退回, 代价只是多一个小请求。
	 */
	private static final String STRICT_SYS_PROMPT =
			"你是精通 Minecraft 模组汉化的翻译助手。用户会给你若干条英文文本(可能带 %s、%1$s 等格式化占位符和 § 颜色代码)。"
			+ "请把它们逐条**翻译成简体中文**,并只输出一个 JSON 对象:键为每条输入里的 key 字段原样,值为对应译文。"
			+ "规则:1) 必须把 %s、%1$s、§x 等占位/颜色代码原样保留在译文中;"
			+ "2) 术语遵循中文 Minecraft 社区习惯;"
			+ "3) 只有**品牌名、人名、模组名、纯按键名(Shift/Ctrl/Alt)、代码标识符、纯格式串或纯符号**才允许原样返回;"
			+ "普通英文单词和短语(例如 Inventory=物品栏、Enabled=已启用、Light Updates Warning=光照更新警告、Metadata=元数据)必须给出中文译文;"
			+ "4) 只输出 JSON,不要任何解释、前后缀或代码块标记。";

	/** 单次回答的最大 token 数(deepseek-chat 上限 8192); 个别兼容端点不接受时自动降级到 4096 */
	private static volatile int maxTokens = 8192;
	private static final int FALLBACK_MAX_TOKENS = 4096;

	public Map<String, String> translate(Map<String, String> batch, HanhuaConfig cfg)
			throws IOException, ApiException, InterruptedException {
		return translate(batch, cfg, false);
	}

	/**
	 * @param strict true = 用"必须翻译"的严格提示词(见 {@link #STRICT_SYS_PROMPT}); 用于模型第一次把可翻译的普通词原样退回时再问一次
	 */
	public Map<String, String> translate(Map<String, String> batch, HanhuaConfig cfg, boolean strict)
			throws IOException, ApiException, InterruptedException {
		try {
			return translateOnce(batch, cfg, maxTokens, strict);
		} catch (ApiException e) {
			String msg = e.getMessage() == null ? "" : e.getMessage();
			if (e.status == 400 && maxTokens > FALLBACK_MAX_TOKENS && msg.contains("max_tokens")) {
				maxTokens = FALLBACK_MAX_TOKENS;
				return translateOnce(batch, cfg, maxTokens, strict);      // 端点不认 8192 -> 降级重试一次
			}
			throw e;
		}
	}

	private Map<String, String> translateOnce(Map<String, String> batch, HanhuaConfig cfg, int maxTokens, boolean strict)
			throws IOException, ApiException, InterruptedException {
		JsonArray items = new JsonArray();
		for (Map.Entry<String, String> e : batch.entrySet()) {
			JsonObject o = new JsonObject();
			o.addProperty("key", e.getKey());
			o.addProperty("text", e.getValue());
			items.add(o);
		}

		JsonArray messages = new JsonArray();
		messages.add(system("system", strict ? STRICT_SYS_PROMPT : SYS_PROMPT));
		// 自定义词典(P1): 本批命中的术语强制指定译名; 无命中返回 null, 零额外 tokens
		String glossary = Glossary.termsFor(batch.values());
		if (glossary != null) messages.add(system("user", glossary));
		messages.add(system("user", items.toString()));

		JsonObject body = new JsonObject();
		body.addProperty("model", blankToDefault(cfg.model, "deepseek-chat"));
		body.addProperty("temperature", 0.1);
		body.add("messages", messages);
		body.addProperty("stream", false);
		body.addProperty("max_tokens", maxTokens);

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(cfg.apiEndpoint()))
				.timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + cfg.apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			String snip = resp.body() == null ? "" : resp.body().trim();
			if (snip.length() > 300) snip = snip.substring(0, 300);
			throw new ApiException(resp.statusCode(), "HTTP " + resp.statusCode() + " " + snip);
		}

		Map<String, String> out = new HashMap<>();
		JsonObject root;
		try {
			root = JsonParser.parseString(resp.body()).getAsJsonObject();
		} catch (JsonParseException e) {
			throw new IOException("invalid JSON from API", e);
		}
		JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
		String content = null;
		boolean truncated = false;
		if (choices != null && !choices.isEmpty()) {
			JsonObject choice = choices.get(0).getAsJsonObject();
			if (choice.has("finish_reason") && "length".equals(choice.get("finish_reason").getAsString())) {
				truncated = true;
			}
			JsonObject message = choice.getAsJsonObject("message");
			if (message != null && message.has("content")) content = message.get("content").getAsString();
		}
		if (truncated) {
			// 回答被 max_tokens 截断 -> 让上层改用小批量重试(否则整批都会解析失败)
			throw new IOException("response truncated by max_tokens (batch too large)");
		}
		if (content != null) {
			JsonObject parsed = extractJsonObject(content);
			if (parsed != null) {
				for (Map.Entry<String, JsonElement> e : parsed.entrySet()) {
					if (e.getValue().isJsonPrimitive()) {
						String v = e.getValue().getAsString();
						if (v != null && !v.isEmpty()) out.put(e.getKey(), v);
					}
				}
			}
		}
		return out;
	}

	private static JsonObject system(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	private static String blankToDefault(String s, String def) {
		return s == null || s.isBlank() ? def : s.trim();
	}

	/** 容错提取 JSON 对象: 容忍 ```json 围栏或前后废话 */
	static JsonObject extractJsonObject(String content) {
		String c = content.trim();
		try {
			JsonElement el = JsonParser.parseString(c);
			if (el.isJsonObject()) return el.getAsJsonObject();
		} catch (JsonParseException ignored) { }
		Pattern fence = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
		Matcher fm = fence.matcher(c);
		if (fm.find()) {
			try { return JsonParser.parseString(fm.group(1)).getAsJsonObject(); } catch (JsonParseException ignored) { }
		}
		int s = c.indexOf('{');
		int e = c.lastIndexOf('}');
		if (s >= 0 && e > s) {
			try { return JsonParser.parseString(c.substring(s, e + 1)).getAsJsonObject(); } catch (JsonParseException ignored) { }
		}
		return null;
	}
}
