package com.hanhua.modmenu;

import com.hanhua.config.HanhuaConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu 集成入口(fabric.mod.json entrypoints.modmenu) */
public class HanhuaModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return HanhuaConfigScreen::build;
	}
}
