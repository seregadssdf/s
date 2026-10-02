package org.zenith.module.player.autosell;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Разбор текста предметов без типов Minecraft, чтобы логику можно было проверить вне игры. */
public final class AutoSellText {
   private static final String[] PRICE_KEYWORDS = {"цена", "стоимость", "price", "cost"};

   private AutoSellText() {
   }

   /** Убирает legacy-коды цвета (§a и т.п.), которые часть серверов оставляет прямо в тексте. */
   public static String plain(String text) {
      if (text == null || text.isEmpty()) {
         return "";
      }

      StringBuilder builder = new StringBuilder(text.length());
      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == '§' && i + 1 < text.length()) {
            i++;
         } else {
            builder.append(c);
         }
      }

      return builder.toString();
   }

   /** Цена лота из строк lore вида «$ Цена: $19,000»; -1, если строки с ценой нет. */
   public static long parsePrice(List<String> lines) {
      for (int i = 0; i < lines.size(); i++) {
         String line = plain(lines.get(i));
         int keyword = keywordIndex(line.toLowerCase(Locale.ROOT));
         if (keyword < 0) {
            continue;
         }

         long value = numberAfterDollar(line);
         if (value < 0L) {
            value = firstNumber(line, keyword);
         }

         // «Цена:» и сумма иногда стоят на соседних строках.
         if (value < 0L && i + 1 < lines.size()) {
            String next = plain(lines.get(i + 1));
            value = numberAfterDollar(next);
            if (value < 0L) {
               value = firstNumber(next, 0);
            }
         }

         if (value >= 0L) {
            return value;
         }
      }

      return -1L;
   }

   /** Индекс в списке, отсортированном по цене: случайно 2-й или 3-й по дешевизне, самый дешёвый — никогда. */
   public static int pickListingIndex(int count, Random random) {
      if (count < 2) {
         return -1;
      }

      return count == 2 ? 1 : 1 + random.nextInt(2);
   }

   public static boolean isEmeraldSwordName(String name) {
      String lower = plain(name).toLowerCase(Locale.ROOT);
      return lower.contains("изумруд") || lower.contains("emerald");
   }

   static int keywordIndex(String lower) {
      for (String keyword : PRICE_KEYWORDS) {
         int index = lower.indexOf(keyword);
         if (index >= 0) {
            return index;
         }
      }

      return -1;
   }

   /** «$ Цена: $19,000» — берётся сумма сразу за знаком доллара, а не первая попавшаяся цифра. */
   static long numberAfterDollar(String line) {
      for (int i = line.indexOf('$'); i >= 0; i = line.indexOf('$', i + 1)) {
         int start = i + 1;
         while (start < line.length() && Character.isSpaceChar(line.charAt(start))) {
            start++;
         }

         if (start < line.length() && Character.isDigit(line.charAt(start))) {
            return readNumber(line, start);
         }
      }

      return -1L;
   }

   static long firstNumber(String line, int from) {
      for (int i = Math.max(0, from); i < line.length(); i++) {
         if (Character.isDigit(line.charAt(i))) {
            return readNumber(line, i);
         }
      }

      return -1L;
   }

   /** Цифры с разделителями разрядов: «19,000», «1 500 000», «2.500». */
   static long readNumber(String line, int start) {
      long value = 0L;
      int digits = 0;

      for (int i = start; i < line.length(); i++) {
         char c = line.charAt(i);
         if (Character.isDigit(c)) {
            if (++digits > 18) {
               return -1L;
            }

            value = value * 10L + (c - '0');
         } else if (!isSeparator(c) || i + 1 >= line.length() || !Character.isDigit(line.charAt(i + 1))) {
            break;
         }
      }

      return digits == 0 ? -1L : value;
   }

   static boolean isSeparator(char c) {
      return c == ',' || c == '.' || c == '\'' || Character.isSpaceChar(c);
   }
}
