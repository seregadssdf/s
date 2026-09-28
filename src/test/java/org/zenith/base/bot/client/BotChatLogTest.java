package org.zenith.base.bot.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.Test;

public final class BotChatLogTest {
   @Test(timeout = 15000L)
   public void persistsBotChat() throws Exception {
      Path gameDirectory = Files.createTempDirectory("bot-chat-log-test-");
      try {
         Path file = gameDirectory.resolve("logs/fatset.txt");
         BotChatLog.write(gameDirectory, "FirstBot", "§aПривет, сервер! §R✓");
         List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
         check(lines.size() == 1, "Create the log under the supplied game directory");
         check(lines.getFirst().matches("\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}] \\[bot=FirstBot] Привет, сервер! ✓"),
            "Include timestamp and bot name, preserve UTF-8, remove formatting");

         String previous = Files.readString(file, StandardCharsets.UTF_8);
         BotChatLog.write(gameDirectory, "SecondBot", "§eAutoSell.bot.v2 [CRAFT_SWORDS]: проверка");
         check(Files.readString(file, StandardCharsets.UTF_8).startsWith(previous), "Append without overwriting earlier messages");
         BotChatLog.write(gameDirectory, "SecondBot", "первая\r\nвторая\n");
         BotChatLog.write(gameDirectory, "SecondBot", "");
         lines = Files.readAllLines(file, StandardCharsets.UTF_8);
         check(lines.size() == 6, "Preserve multiline and blank messages");
         check(lines.get(1).endsWith("[bot=SecondBot] AutoSell.bot.v2 [CRAFT_SWORDS]: проверка"), "Keep bot diagnostics");
         check(lines.subList(2, 6).stream().allMatch(line -> line.contains("[bot=SecondBot] ")), "Prefix every physical line");

         List<Callable<Void>> tasks = new ArrayList<>();
         for (int bot = 0; bot < 4; bot++) {
            String name = "ConcurrentBot" + bot;
            tasks.add(() -> {
               for (int i = 0; i < 75; i++) {
                  BotChatLog.write(gameDirectory, name, "message-" + i);
               }
               return null;
            });
         }
         try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(tasks)) {
               result.get();
            }
         }
         lines = Files.readAllLines(file, StandardCharsets.UTF_8);
         check(lines.size() == 306, "Keep more than 200 messages without losing concurrent writes");
         var messages = new HashSet<String>();
         for (String line : lines.subList(6, lines.size())) {
            check(line.matches("\\[.*] \\[bot=ConcurrentBot[0-3]] message-\\d+"), "Do not interleave lines from different bots");
            messages.add(line.substring(line.indexOf("[bot=")));
         }
         check(messages.size() == 300, "Write each concurrent message exactly once");

         Path blockedDirectory = gameDirectory.resolve("blocked");
         Files.createDirectories(blockedDirectory);
         Files.writeString(blockedDirectory.resolve("logs"), "not a directory");
         BotChatLog.write(blockedDirectory, "FirstBot", "unwritable destination");
         BotChatLog.write(blockedDirectory, "FirstBot", "still unwritable");
         Files.delete(blockedDirectory.resolve("logs"));
         BotChatLog.write(blockedDirectory, "FirstBot", "recovered");
         check(Files.readString(blockedDirectory.resolve("logs/fatset.txt")).endsWith("recovered" + System.lineSeparator()),
            "Do not throw on I/O failure; retry on subsequent messages");
      } finally {
         try (var paths = Files.walk(gameDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
               Files.delete(path);
            }
         }
      }
   }

   private static void check(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
