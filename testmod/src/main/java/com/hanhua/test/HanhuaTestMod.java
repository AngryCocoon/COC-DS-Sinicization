package com.hanhua.test;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 验收辅助 mod：注册一个只有 en_us 翻译的测试物品。
 * 26.2 数据驱动风格: Item.Properties 必须先 setId(ResourceKey)。
 */
public class HanhuaTestMod implements ClientModInitializer {
	public static final String MOD_ID = "hanhua_test";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> DUCK_KEY = ResourceKey.create(
			BuiltInRegistries.ITEM.key(), Identifier.fromNamespaceAndPath(MOD_ID, "rubber_duck"));

	public static final Item RUBBER_DUCK = new Item(new Item.Properties().setId(DUCK_KEY));

	@Override
	public void onInitializeClient() {
		Registry.register(BuiltInRegistries.ITEM, DUCK_KEY, RUBBER_DUCK);
		LOGGER.info("[hanhua_test] registered Rubber Duck (en_us only) -> item.hanhua_test.rubber_duck");
	}
}
