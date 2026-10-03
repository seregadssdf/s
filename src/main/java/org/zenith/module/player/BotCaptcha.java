package org.zenith.module.player;

import org.zenith.module.Category;
import org.zenith.module.Module;
import org.zenith.module.ModuleInfo;
import org.zenith.setting.TextSetting;
import org.zenith.setting.BooleanSetting;

@ModuleInfo(name = "BotCaptcha", category = Category.PLAYER,
   description = "Общий ключ OCR.Space для AutoCapcha всех ботов; включать этот модуль не требуется")
public final class BotCaptcha extends Module {
   public static final BotCaptcha botCaptcha = new BotCaptcha();
   public final TextSetting apiKey = new TextSetting("OCR.Space API key",
      "Общий ключ для всех ботов; не публикуйте конфиг", "", "Введите API-ключ").secret();
   public final BooleanSetting compareEngines = new BooleanSetting("Сравнить OCR 1 и 2",
      "Два запроса на капчу; при разных пятизначных ответах ничего не отправляется", true);

   private BotCaptcha() {
   }
}
