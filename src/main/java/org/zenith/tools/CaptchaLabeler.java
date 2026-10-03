package org.zenith.tools;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

// Ручная разметка: показывает <runDir>/captcha/hard/all/N.png по порядку.
// Пять цифр + Enter переименовывают файл в <цифры>.png; пустой Enter пропускает файл.
public final class CaptchaLabeler {
   private static final Pattern QUEUED = Pattern.compile("[0-9]+\\.png");
   private static final Pattern ANSWER = Pattern.compile("[0-9]{5}");

   private final List<Path> queue = new ArrayList<>();
   private final JFrame frame = new JFrame("Разметка капч");
   private final JLabel image = new JLabel("", SwingConstants.CENTER);
   private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
   private final JTextField input = new JTextField(12);
   private int position;

   private CaptchaLabeler(Path inbox) throws IOException {
      try (var files = Files.list(inbox)) {
         for (Path file : files.toArray(Path[]::new)) {
            if (QUEUED.matcher(file.getFileName().toString()).matches()) {
               this.queue.add(file);
            }
         }
      }
      this.queue.sort(java.util.Comparator.comparingInt(CaptchaLabeler::index));
      this.input.addActionListener(event -> this.submit());
      JButton skip = new JButton("Пропустить");
      skip.addActionListener(event -> {
         this.position++;
         this.show();
      });
      JPanel bottom = new JPanel(new java.awt.FlowLayout());
      bottom.add(this.input);
      bottom.add(skip);
      JFrame jframe = this.frame;
      jframe.setLayout(new BorderLayout());
      jframe.add(this.status, BorderLayout.NORTH);
      jframe.add(this.image, BorderLayout.CENTER);
      jframe.add(bottom, BorderLayout.SOUTH);
      jframe.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
      jframe.setSize(780, 660);
      jframe.setLocationRelativeTo(null);
   }

   private static int index(Path file) {
      String name = file.getFileName().toString();
      return Integer.parseInt(name.substring(0, name.length() - 4));
   }

   private void show() {
      this.input.setText("");
      if (this.position >= this.queue.size()) {
         this.image.setIcon(null);
         this.status.setText("Все файлы обработаны. Новые капчи появятся после запуска ботов.");
         return;
      }
      Path current = this.queue.get(this.position);
      try {
         BufferedImage image = ImageIO.read(current.toFile());
         if (image == null) {
            this.status.setText("Файл не читается как изображение: " + current.getFileName());
            return;
         }
         this.image.setIcon(new ImageIcon(image.getScaledInstance(-1, 420, Image.SCALE_SMOOTH)));
         this.status.setText((this.position + 1) + " / " + this.queue.size() + "   " + current.getFileName()
            + "   (Enter — записать цифры, пустой Enter — пропустить)");
      } catch (IOException exception) {
         this.status.setText("Не удалось прочитать: " + exception.getClass().getSimpleName());
      }
   }

   private void submit() {
      if (this.position >= this.queue.size()) {
         return;
      }
      String answer = this.input.getText().trim();
      if (!ANSWER.matcher(answer).matches()) {
         this.status.setText("Нужно ровно 5 цифр; пустое поле — пропустить");
         return;
      }
      Path current = this.queue.get(this.position);
      try {
         Files.move(current, freeTarget(current.getParent(), answer));
      } catch (IOException exception) {
         this.status.setText("Не удалось переименовать: " + exception.getClass().getSimpleName());
         return;
      }
      this.position++;
      this.show();
   }

   public static Path freeTarget(Path directory, String digits) {
      Path target = directory.resolve(digits + ".png");
      int copy = 2;
      while (Files.exists(target)) {
         target = directory.resolve(digits + "_" + copy++ + ".png");
      }
      return target;
   }

   public static void main(String[] args) throws Exception {
      Path inbox = Path.of(args.length > 0 ? args[0] : "run/captcha/hard/all");
      if (!Files.isDirectory(inbox)) {
         System.err.println("Папка не найдена: " + inbox.toAbsolutePath());
         System.exit(1);
         return;
      }
      CaptchaLabeler labeler = new CaptchaLabeler(inbox);
      SwingUtilities.invokeLater(() -> labeler.frame.setVisible(true));
   }
}
