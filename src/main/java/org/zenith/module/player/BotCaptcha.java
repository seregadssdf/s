package org.zenith.module.player;

import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.TextSetting;

@ModuleInfo(name = "BotCaptcha", category = Category.PLAYER,
   description = "Общий ключ OCR.Space для AutoCapcha всех ботов; включать этот модуль не требуется")
public final class BotCaptcha extends Module {
   public static final BotCaptcha botCaptcha = new BotCaptcha();
   public final TextSetting apiKey = new TextSetting("OCR.Space API key",
      "Общий ключ для всех ботов; не публикуйте конфиг", "", "Введите API-ключ").secret();

   private BotCaptcha() {
   }
}
