package org.zenith.base.bot.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Small persistent bot lifecycle log that is easy to inspect after a failed launch. */
public final class BotStartupLog {
   private static final Path FILE = Path.of("run", "logs", "bots.log");
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

   private BotStartupLog() {
   }

   public static synchronized void info(String bot, String message) {
      write("INFO", bot, message, null);
   }

   public static synchronized void error(String bot, String message, Throwable throwable) {
      write("ERROR", bot, message, throwable);
   }

   private static void write(String level, String bot, String message, Throwable throwable) {
      StringBuilder line = new StringBuilder()
         .append('[').append(LocalDateTime.now().format(TIME)).append("] [").append(level).append("] [bot=")
         .append(bot).append("] ").append(message).append(System.lineSeparator());
      if (throwable != null) {
         line.append(throwable).append(System.lineSeparator());
         for (StackTraceElement element : throwable.getStackTrace()) {
            line.append("    at ").append(element).append(System.lineSeparator());
         }
      }

      try {
         Files.createDirectories(FILE.getParent());
         Files.writeString(FILE, line.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException ignored) {
         // The normal logger still receives the same failure if the diagnostic file is unavailable.
      }
   }
}
