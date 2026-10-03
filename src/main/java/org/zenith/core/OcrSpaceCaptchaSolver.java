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
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class OcrSpaceCaptchaSolver {
   private static final URI ENDPOINT = URI.create("https://api.ocr.space/parse/image");
   private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
   private static final Semaphore REQUESTS = new Semaphore(1, true);

   private OcrSpaceCaptchaSolver() {
   }

   public static String solve(byte[] png, String configuredKey, boolean compareEngines, BooleanSupplier current,
      Consumer<String> diagnostics) throws IOException, InterruptedException {
      String key = configuredKey == null ? "" : configuredKey.trim();
      if (!key.matches("[A-Za-z0-9]{8,128}")) {
         throw new IOException("Invalid OCR.Space API key configuration");
      }
      REQUESTS.acquire();
      try {
         if (!current.getAsBoolean()) {
            return null;
         }
         String first = request(png, key, ENDPOINT, CLIENT, 2, diagnostics);
         if (!compareEngines || !current.getAsBoolean()) {
            return first;
         }
         String second = request(png, key, ENDPOINT, CLIENT, 1, diagnostics);
         if (first != null && second != null && !first.equals(second)) {
            diagnostics.accept("OCR engines disagree; answer withheld");
         }
         return chooseAnswer(first, second);
      } finally {
         REQUESTS.release();
      }
   }

   static String chooseAnswer(String first, String second) {
      if (first != null && second != null && !first.equals(second)) {
         return null;
      }
      return first != null ? first : second;
   }

   static String request(byte[] png, String key, URI endpoint, HttpClient client) throws IOException, InterruptedException {
      return request(png, key, endpoint, client, 2, message -> { });
   }

   static String request(byte[] png, String key, URI endpoint, HttpClient client, int engine,
      Consumer<String> diagnostics) throws IOException, InterruptedException {
      if (png.length == 0 || png.length > 1_000_000) {
         throw new IOException("OCR.Space image must be between 1 byte and 1 MB");
      }
      String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
      String body = "base64Image=" + URLEncoder.encode(image, StandardCharsets.UTF_8)
         + "&language=eng&OCREngine=" + engine + "&isOverlayRequired=false&filetype=PNG";
      HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
         .header("apikey", key).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
         .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
         throw new IOException("OCR.Space HTTP " + response.statusCode());
      }
      String answer = parseAnswer(response.body(), text -> diagnostics.accept("OCR engine " + engine
         + " ParsedText=" + safeDiagnostic(text, key)));
      diagnostics.accept("OCR engine " + engine + (answer == null ? " rejected: expected five digits" : " valid five-digit candidate"));
      return answer;
   }

   static String parseAnswer(String body) throws IOException {
      return parseAnswer(body, text -> { });
   }

   static String safeDiagnostic(String text, String key) {
      String redacted = text.replace(key, "[REDACTED]");
      StringBuilder safe = new StringBuilder();
      redacted.codePoints().limit(160).forEach(c -> safe.appendCodePoint(Character.isISOControl(c)
         || Character.getType(c) == Character.FORMAT ? ' ' : c));
      return '"' + safe.toString() + '"';
   }

   static String parseAnswer(String body, Consumer<String> diagnostics) throws IOException {
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
         diagnostics.accept(text);
         // Do not turn multiple candidates or OCR substitutions into a chat answer.
         return text.matches("[0-9]{5}") ? text : null;
      } catch (RuntimeException exception) {
         throw new IOException("Invalid OCR.Space response");
      }
   }
}
