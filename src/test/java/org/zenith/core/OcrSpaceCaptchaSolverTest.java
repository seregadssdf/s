package org.zenith.core;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

public final class OcrSpaceCaptchaSolverTest {
   @org.junit.Test
   public void validatesParserAndHttpContract() throws Exception {
      main(new String[0]);
   }

   private static String response(String text) {
      return "{\"IsErroredOnProcessing\":false,\"OCRExitCode\":1,\"ParsedResults\":[{\"FileParseExitCode\":1,\"ParsedText\":\""
         + text + "\"}]}";
   }

   public static void main(String[] args) throws Exception {
      check("06401".equals(OcrSpaceCaptchaSolver.parseAnswer(response("06401\\r\\n"))), "Leading zero");
      for (String text : new String[]{"1234", "123456", "12O45", "/12345", "12345 67890", "12 345", ""}) {
         check(OcrSpaceCaptchaSolver.parseAnswer(response(text)) == null, "Unsafe answer accepted");
      }
      for (String invalid : new String[]{"not json", "{}", "{\"IsErroredOnProcessing\":true}",
         response("12345").replace("\"OCRExitCode\":1", "\"OCRExitCode\":2"),
         response("12345").replace("\"FileParseExitCode\":1", "\"FileParseExitCode\":-10")}) {
         try {
            OcrSpaceCaptchaSolver.parseAnswer(invalid);
            throw new AssertionError("Error response accepted");
         } catch (IOException expected) {
         }
      }
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/ok", exchange -> {
         String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
         boolean valid = "POST".equals(exchange.getRequestMethod())
            && "test-key".equals(exchange.getRequestHeaders().getFirst("apikey"))
            && body.contains("base64Image=data%3Aimage%2Fpng%3Bbase64%2C") && body.contains("OCREngine=2")
            && !body.contains("test-key");
         byte[] data = response("12345").getBytes(StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(valid ? 200 : 400, data.length);
         exchange.getResponseBody().write(data);
         exchange.close();
      });
      server.createContext("/limit", exchange -> {
         exchange.sendResponseHeaders(429, -1);
         exchange.close();
      });
      server.start();
      try {
         String base = "http://127.0.0.1:" + server.getAddress().getPort();
         check("12345".equals(OcrSpaceCaptchaSolver.request(new byte[]{1, 2, 3}, "test-key", URI.create(base + "/ok"),
            HttpClient.newHttpClient())), "HTTP request contract");
         try {
            OcrSpaceCaptchaSolver.request(new byte[]{1}, "test-key", URI.create(base + "/limit"), HttpClient.newHttpClient());
            throw new AssertionError("Rate limit accepted");
         } catch (IOException expected) {
            check(!expected.getMessage().contains("test-key"), "Secret in error");
         }
      } finally {
         server.stop(0);
      }
      System.out.println("OCR.Space parser and HTTP contract tests passed");
   }

   private static void check(boolean condition, String message) {
      if (!condition) {
         throw new AssertionError(message);
      }
   }
}
