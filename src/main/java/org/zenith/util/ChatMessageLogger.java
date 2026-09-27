package org.zenith.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class ChatMessageLogger {
   private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
   private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private static final Object WRITE_LOCK = new Object();

   private ChatMessageLogger() {
   }

   public static void initialize() {
      ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
         if (!overlay) {
            write(message);
         }
      });
      ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> write(message));
   }

   private static void write(Text message) {
      String line = "[" + TIMESTAMP.format(LocalDateTime.now()) + "] " + message.getString();
      Path directory = MinecraftClient.getInstance().runDirectory.toPath().resolve("chat-logs");
      Path file = directory.resolve(FILE_DATE.format(LocalDate.now()) + ".log");

      synchronized (WRITE_LOCK) {
         try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(
               file,
               StandardCharsets.UTF_8,
               StandardOpenOption.CREATE,
               StandardOpenOption.APPEND
            )) {
               writer.write(line);
               writer.newLine();
            }
         } catch (IOException ignored) {
            // Chat logging is best-effort and must never crash or interrupt the client.
         }
      }
   }
}
