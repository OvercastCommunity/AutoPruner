package oc.tc.autopruner;

import com.formdev.flatlaf.FlatLightLaf;
import net.querz.mca.Chunk;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.mca.Section;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.logging.Logger;

public class AutoPruner {

  public static Logger logger;

  public static void main(String[] args) {
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tF %1$tT %4$s %2$s %5$s%6$s%n");
    logger = Logger.getLogger("AutoPruner");
    CommandLine cmd = processOptions(args);
    if (cmd == null) return;

    if (cmd.hasOption("file")) {
      String filePath = cmd.getOptionValue("file");
      pruneMCAFile(filePath, null);
    } else if (cmd.hasOption("directory")) {
      String directoryPath = cmd.getOptionValue("directory");
      long sizeDeleted = recursivelyProcessFiles(new File(directoryPath), 0, null);
      logger.info("Deleted " + readableFileSize(sizeDeleted) + " from: " + directoryPath);
    } else {
      System.out.println("Must specify --file or --directory to use as a cli");
      FlatLightLaf.setup();

      //Schedule a job for the event-dispatching thread:
      //creating and showing this application's GUI.
      javax.swing.SwingUtilities.invokeLater(() -> {
        JFrame frame = new JFrame("AutoPruner");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);

        JScrollPane areaScrollPane = new JScrollPane(textArea);
        areaScrollPane.setVerticalScrollBarPolicy(
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        areaScrollPane.setPreferredSize(new Dimension(900, 400));

        frame.add(areaScrollPane);
        frame.pack();
        frame.setVisible(true);

        JFileChooser fc = new JFileChooser();
        fc.setCurrentDirectory(new File(""));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int option = fc.showDialog(frame, "Prune Map(s)");

        if (option == JFileChooser.APPROVE_OPTION) {
          File file = fc.getSelectedFile();
          System.out.println("Selected: " + file.getAbsolutePath());
          textArea.append("Selected: " + file.getAbsolutePath() + "\n");

          new SwingWorker() {
            @Override
            protected Object doInBackground() throws Exception {
              recursivelyProcessFiles(file, 28, textArea);
              return null;
            }
          }.execute();

        } else {
          System.exit(0);
        }
      });
    }
  }

  private static void addTextToFrame(String text, JTextArea jTextArea) {
    jTextArea.append(text + "\n");
  }

  private static long recursivelyProcessFiles(File file, long depth, JTextArea container) {
    long sizeDeleted = 0;
    if (depth > 30) {
      return 0;
    }
    File[] files = file.listFiles();
    if (files != null) {
      for (File childFile : files) {
        if (childFile.isDirectory()) {
          sizeDeleted += recursivelyProcessFiles(childFile, depth, container);
        } else if (childFile.isFile() && childFile.getName().endsWith(".mca")) {
          sizeDeleted += pruneMCAFile(childFile.getAbsolutePath(), container);
        }
      }
    }
    return sizeDeleted;
  }

  private static long pruneMCAFile(String path, JTextArea container) {
    long sizeChange = 0;
    try {
      File regionFile = new File(path);
      long initialSize = regionFile.length();
      MCAFile mcaFile = MCAUtil.read(regionFile);
      boolean regionFileEmpty = true;

      for (int i = 0; i < 1024; i++) {
        Chunk chunk = mcaFile.getChunk(i);

        if (chunk == null) continue;

        ListTag<CompoundTag> entities = chunk.getEntities();
        if (entities != null && entities.size() > 0) {
          regionFileEmpty = false;
          continue;
        }

        ListTag<CompoundTag> tileEntities = chunk.getTileEntities();
        if (tileEntities != null && tileEntities.size() > 0) {
          regionFileEmpty = false;
          continue;
        }

        boolean empty = true;

        for (int y = 0; y < 16; y++) {
          Section section = chunk.getSection(y);
          if (section != null && !section.isEmpty()) {
            empty = false;
            regionFileEmpty = false;
            break;
          }
        }

        if (empty && !chunk.hasSpecialBiomes()) {
          mcaFile.setChunk(i, null);
        }
      }
      if (regionFileEmpty) {
        Files.deleteIfExists(Paths.get(path));
        String deleteMessage = "Deleted file (" + readableFileSize(initialSize) + ") : " + path;
        logger.info(deleteMessage);
        if (container != null) {
          addTextToFrame(deleteMessage, container);
        }
        sizeChange = initialSize;
      } else {
        MCAUtil.write(mcaFile, path);
        long newSize = regionFile.length();
        sizeChange = initialSize - newSize;
        String deleteMessage = "Deleted " + readableFileSize(sizeChange) + " from: " + path;
        logger.info(deleteMessage);
        if (container != null) {
          addTextToFrame(deleteMessage, container);
        }
      }
    } catch (Exception e) {
      String warnMessage = "Failed to parse file: " + path + ", " + e.getMessage();
      logger.warning(warnMessage);
      if (container != null) {
        addTextToFrame(warnMessage, container);
      }
      e.printStackTrace();
    }
    return sizeChange;
  }

  public static String readableFileSize(long size) {
    if (size <= 0) return "0";
    final String[] units = new String[]{"B", "kB", "MB", "GB", "TB"};
    int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
    return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
  }

  private static CommandLine processOptions(String[] args) {
    Options options = new Options();

    Option fileOption = new Option(
        "f",
        "file",
        true,
        "Path for .mca file to prune");
    fileOption.setRequired(false);
    options.addOption(fileOption);

    Option directoryOption = new Option(
        "d",
        "directory",
        true,
        "Path for directory containing .mca files");
    directoryOption.setRequired(false);
    options.addOption(directoryOption);

    CommandLineParser parser = new DefaultParser();
    HelpFormatter formatter = new HelpFormatter();
    try {
      return parser.parse(options, args);
    } catch (ParseException e) {
      System.out.println(e.getMessage());
      formatter.printHelp("java -jar AutoPruner.jar", options);
      return null;
    }
  }
}
