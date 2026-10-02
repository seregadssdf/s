package org.zenith.module.player.autosell;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор текста предметов без типов Minecraft, чтобы логику можно было проверить вне игры. */
public final class AutoSellText {
   /** Число цены с разделителями и суффиксами: «1 999 999», «1.5кк», «200тыс», «3m», «2б». */
   private static final String PRICE_NUMBER = "[0-9][0-9\\s.,]*(?:\\s*(?:кк|kk|к|k|тыс\\.?|m|млн\\.?|мил(?:лион)?\\.?|b|б))?";
   private static final Pattern NUMBER_PATTERN = Pattern.compile("(" + PRICE_NUMBER + ")",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
   private static final Pattern CURRENCY_BEFORE_NUMBER_PATTERN = Pattern.compile("\\$\\s*(" + PRICE_NUMBER + ")",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
   private static final Pattern NUMBER_BEFORE_CURRENCY_PATTERN = Pattern.compile("(" + PRICE_NUMBER + ")\\s*\\$",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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

   /** Цена лота из lore: «Цена: $199,999», «$19,000», «1.5кк», «Цена: 200тыс»; -1, если цены нет. */
   public static long parsePrice(List<String> lines) {
      long currencyFallback = -1L;
      for (int i = 0; i < lines.size(); i++) {
         String line = normalizeLine(lines.get(i));
         if (line.isEmpty()) {
            continue;
         }

         long price = priceFromLine(line);
         if (price < 0L) {
            continue;
         }

         if (isPriceKeywordLine(line)) {
            return price;
         }

         if (line.indexOf('$') >= 0 && currencyFallback < 0L) {
            currencyFallback = price;
         }
      }

      return currencyFallback;
   }

   /** Строка к разбору: юникодные доллары и неразрывные пробелы, цветовые коды и лишние пробелы долой. */
   private static String normalizeLine(String line) {
      if (line == null) {
         return "";
      }

      return line
         .replace('＄', '$')
         .replace('﹩', '$')
         .replace('\u00A0', ' ')
         .replaceAll("(?i)§[0-9a-fk-or]", "")
         .replaceAll("\\s+", " ")
         .trim();
   }

   /** «$199,999» — сначала сумма за долларом, потом перед ним, потом число в строке с ключевым словом цены. */
   private static long priceFromLine(String line) {
      Matcher currencyBefore = CURRENCY_BEFORE_NUMBER_PATTERN.matcher(line);
      if (currencyBefore.find()) {
         long price = parseNumber(currencyBefore.group(1));
         if (price >= 0L) {
            return price;
         }
      }

      Matcher numberBefore = NUMBER_BEFORE_CURRENCY_PATTERN.matcher(line);
      if (numberBefore.find()) {
         long price = parseNumber(numberBefore.group(1));
         if (price >= 0L) {
            return price;
         }
      }

      if (!isPriceKeywordLine(line)) {
         return -1L;
      }

      Matcher number = NUMBER_PATTERN.matcher(line);
      while (number.find()) {
         long price = parseNumber(number.group(1));
         if (price >= 0L) {
            return price;
         }
      }

      return -1L;
   }

   private static boolean isPriceKeywordLine(String line) {
      String lower = line.toLowerCase(Locale.ROOT);
      return lower.contains("цен")
         || lower.contains("стоим")
         || lower.contains("продаж")
         || lower.contains("price")
         || lower.contains("cost");
   }

   /** «199,999» -> 199999, «1.5кк» -> 1500000, «200тыс» -> 200000; суффиксы -к/-kk/-млн/-тыс/-m/-b. */
   private static long parseNumber(String value) {
      String raw = value.toLowerCase(Locale.ROOT)
         .replace('\u00A0', ' ')
         .replaceAll("\\s+", "")
         .trim();
      if (raw.isEmpty()) {
         return -1L;
      }

      long multiplier = 1L;
      if (raw.matches(".*(кк|kk)$")) {
         multiplier = 1000000L;
         raw = raw.substring(0, raw.length() - 2);
      } else if (raw.matches(".*(млн\\.?|мил(?:лион)?\\.?)$")) {
         multiplier = 1000000L;
         raw = raw.replaceFirst("(млн\\.?|мил(?:лион)?\\.?)$", "");
      } else if (raw.matches(".*(тыс\\.?)$")) {
         multiplier = 1000L;
         raw = raw.replaceFirst("тыс\\.?", "");
      } else if (raw.matches(".*[кk]$")) {
         multiplier = 1000L;
         raw = raw.substring(0, raw.length() - 1);
      } else if (raw.matches(".*m$")) {
         multiplier = 1000000L;
         raw = raw.substring(0, raw.length() - 1);
      } else if (raw.matches(".*[bб]$")) {
         multiplier = 1000000000L;
         raw = raw.substring(0, raw.length() - 1);
      }

      if (multiplier > 1L) {
         String decimal = normalizeDecimal(raw);
         if (decimal.isEmpty()) {
            return -1L;
         }

         try {
            return Math.round(Double.parseDouble(decimal) * (double) multiplier);
         } catch (NumberFormatException var7) {
            return -1L;
         }
      }

      String digits = raw.replaceAll("[^0-9]", "");
      if (digits.isEmpty()) {
         return -1L;
      }

      try {
         return Long.parseLong(digits);
      } catch (NumberFormatException var6) {
         return -1L;
      }
   }

   /** «1,500» -> «1500», «1.500» -> «1500», «1,5» -> «1.5», «1.500,25» -> «1500.25»: разделитель тысяч — только группы ровно по 3 цифры. */
   private static String normalizeDecimal(String raw) {
      String normalized = raw.replace('\u00A0', ' ');
      boolean hasComma = normalized.contains(",");
      boolean hasDot = normalized.contains(".");
      if (hasComma && hasDot) {
         // Если запятая идёт после точки — она десятичная («1.500,25»), иначе запятая тысяч («1,500.25»).
         return normalized.lastIndexOf(',') > normalized.lastIndexOf('.')
            ? normalized.replace(".", "").replace(',', '.')
            : normalized.replace(",", "");
      }

      if (hasComma) {
         return normalized.matches("\\d{1,3}(,\\d{3})+") ? normalized.replace(",", "") : normalized.replace(",", ".");
      }

      if (hasDot) {
         return normalized.matches("\\d{1,3}(\\.\\d{3})+") ? normalized.replace(".", "") : normalized;
      }

      return normalized;
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
}
