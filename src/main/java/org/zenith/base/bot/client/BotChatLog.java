package org.zenith.base.bot.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BotChatLog {
   private static final Logger LOGGER = LoggerFactory.getLogger(BotChatLog.class);
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
   private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");
   private static final Pattern LINE_BREAK = Pattern.compile("\\R");
   private static boolean writeFailed;

   private BotChatLog() {
   }

   static synchronized void write(Path gameDirectory, String bot, String message) {
      Path file = gameDirectory.resolve("logs").resolve("fatset.txt");
      String name = LINE_BREAK.matcher(FORMATTING.matcher(bot).replaceAll("")).replaceAll(" ");
      String prefix = "[" + LocalDateTime.now().format(TIME) + "] [bot=" + name + "] ";
      StringBuilder lines = new StringBuilder();
      for (String line : LINE_BREAK.split(FORMATTING.matcher(message).replaceAll(""), -1)) {
         lines.append(prefix).append(line).append(System.lineSeparator());
      }

      try {
         Files.createDirectories(file.getParent());
         Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         writeFailed = false;
      } catch (IOException | SecurityException exception) {
         if (!writeFailed) {
            LOGGER.warn("Unable to append bot chat to {}", file, exception);
            writeFailed = true;
         }
      }
   }
}
