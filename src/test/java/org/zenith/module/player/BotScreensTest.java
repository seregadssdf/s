package org.zenith.module.player;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import static org.junit.Assert.*;

public class BotScreensTest {
   @Test
   public void wallOrientationAndRaySelection() {
      for (Direction face : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
         BotScreens.Monitor monitor = new BotScreens.Monitor("bot", Vec3d.ZERO, face);
         Vec3d normal = monitor.normal();
         assertEquals(0, normal.dotProduct(monitor.right()), 0.00001);
         assertEquals(1, monitor.right().length(), 0.00001);
         Vec3d eye = normal.multiply(4);
         assertEquals(4, monitor.intersection(eye, normal.negate()), 0.00001);
         assertEquals(-1, monitor.intersection(eye.add(monitor.right().multiply(2)), normal.negate()), 0);
         assertEquals(-1, monitor.intersection(eye.add(0, 1, 0), normal.negate()), 0);
         assertEquals(-1, monitor.intersection(normal.multiply(-4), normal), 0);
         assertEquals(-1, monitor.intersection(eye, monitor.right()), 0);
      }
   }
}
