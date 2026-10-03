package org.zenith.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CaptchaLabelerTest {
   @Test
   public void preservesLeadingZeroAndResolvesCollisions() throws Exception {
      java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("labels");
      assertEquals("06401.png", CaptchaLabeler.freeTarget(dir, "06401").getFileName().toString());
      Files.createFile(dir.resolve("06401.png"));
      assertEquals("06401_2.png", CaptchaLabeler.freeTarget(dir, "06401").getFileName().toString());
      Files.createFile(dir.resolve("06401_2.png"));
      assertEquals("06401_3.png", CaptchaLabeler.freeTarget(dir, "06401").getFileName().toString());
   }

}
