package org.zenith.module.player.autosell;

/** Настройки цикла; ими владеет модуль AutoSell во вкладке PvE, боты читают их оттуда же. */
public interface AutoSellSettings {
   /** Цена для /ah sellgui; 0 — не задана. */
   long priceValue();

   boolean debugEnabled();
}
