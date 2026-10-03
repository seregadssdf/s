package org.zenith.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

public final class OcrSpaceCaptchaSolver {
   private static final URI ENDPOINT = URI.create("https://api.ocr.space/parse/image");
   private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
   private static final Semaphore REQUESTS = new Semaphore(1, true);

   private OcrSpaceCaptchaSolver() {
   }

   public static String solve(byte[] png, String configuredKey, Path runDirectory, BooleanSupplier current) throws IOException, InterruptedException {
      String key = configuredKey == null ? "" : configuredKey.trim();
      if (key.isBlank()) {
         key = System.getenv("OCR_SPACE_API_KEY");
      }
      if (key == null || key.isBlank()) {
         Path file = runDirectory.resolve("config/ocr-space.key");
         if (!Files.isRegularFile(file)) {
            throw new IOException("Set the OCR.Space API key in BotAutoCapcha settings");
         }
         key = Files.readString(file, StandardCharsets.UTF_8).trim();
      }
      if (!key.matches("[A-Za-z0-9]{8,128}")) {
         throw new IOException("Invalid OCR.Space API key configuration");
      }
      REQUESTS.acquire();
      try {
         if (!current.getAsBoolean()) {
            return null;
         }
         return request(png, key, ENDPOINT, CLIENT);
      } finally {
         REQUESTS.release();
      }
   }

   static String request(byte[] png, String key, URI endpoint, HttpClient client) throws IOException, InterruptedException {
      if (png.length == 0 || png.length > 1_000_000) {
         throw new IOException("OCR.Space image must be between 1 byte and 1 MB");
      }
      String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
      String body = "base64Image=" + URLEncoder.encode(image, StandardCharsets.UTF_8)
         + "&language=eng&OCREngine=2&isOverlayRequired=false&filetype=PNG";
      HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
         .header("apikey", key).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
         .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
         throw new IOException("OCR.Space HTTP " + response.statusCode());
      }
      return parseAnswer(response.body());
   }

   static String parseAnswer(String body) throws IOException {
      try {
         JsonObject root = JsonParser.parseString(body).getAsJsonObject();
         if (!root.has("IsErroredOnProcessing") || root.get("IsErroredOnProcessing").getAsBoolean()
            || !root.has("OCRExitCode") || root.get("OCRExitCode").getAsInt() != 1) {
            throw new IOException("OCR.Space processing failed");
         }
         JsonArray results = root.getAsJsonArray("ParsedResults");
         if (results == null || results.size() != 1) {
            return null;
         }
         JsonObject result = results.get(0).getAsJsonObject();
         if (!result.has("FileParseExitCode") || result.get("FileParseExitCode").getAsInt() != 1) {
            throw new IOException("OCR.Space image parsing failed");
         }
         if (!result.has("ParsedText") || result.get("ParsedText").isJsonNull()) {
            return null;
         }
         String text = result.get("ParsedText").getAsString().strip();
         // Do not turn multiple candidates or OCR substitutions into a chat answer.
         return text.matches("[0-9]{5}") ? text : null;
      } catch (RuntimeException exception) {
         throw new IOException("Invalid OCR.Space response");
      }
   }
}
